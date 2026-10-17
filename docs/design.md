# Design notes

The details behind the [README](../README.md): every decision with its reasoning, the trade-offs and rejected alternatives, tests, CI, configuration and code layout.

## Ledger design

**Chart of accounts**, one set per currency. Postings are signed minor units: debit > 0, credit < 0.

| Account | Kind | Normal side | Meaning |
|---|---|---|---|
| `psp_clearing` | asset | debit | Money collected from customers, held at or receivable from the PSP |
| `tax_payable:{jurisdiction}` | liability | credit | Owed to that tax authority |
| `merchant_payable:{merchantId}` | liability | credit | Owed to the merchant |
| `fee_revenue` | revenue | credit | The MoR's fee |

**Worked example.** €121.00 paid in NL at 21% VAT, with a 5% MoR fee:

| Event | Dr | Cr |
|---|---|---|
| Capture €121.00 | `psp_clearing` 121.00 | `tax_payable:NL` 21.00 · `fee_revenue` 6.05 · `merchant_payable:acme` 93.95 |
| Refund €60.50 (fee kept) | `tax_payable:NL` 10.50 · `merchant_payable:acme` 50.00 | `psp_clearing` 60.50 |
| Refund the rest, €60.50 | `tax_payable:NL` 10.50 · `merchant_payable:acme` 50.00 | `psp_clearing` 60.50 |
| **Result** | Tax owed **0** · merchant **−6.05** · fee revenue **6.05** · clearing **0** | |

With `REFUND_FEE_POLICY=MOR_RETURNS_FEE`, the refunds also debit `fee_revenue` and every balance returns to 0.

**Balances.** Each account stores its running `balance`. It moves in the **same transaction** as the postings, via an atomic `UPDATE ledger.accounts SET balance = balance + x`:
- the current report reads it directly (O(1) per account);
- an `asOf` report sums the postings up to that time (the history);
- `LedgerTable.verify` reconciles stored balances against the postings, and the tests run it after the money flows.

This is the model the established ledgers use: TigerBeetle keeps debit/credit counters on each account; Modern Treasury keeps balances on the account with a lock version.

**Balanced, single-currency entries** are enforced in code: the pure `validatedPostings` function checks every entry through `LedgerWriter`, the shared write path for both balance modes. `/v1/ledger/verify` detects anything written around it. A deferred constraint trigger did the same check in the database; profiling showed it ran once per inserted row (5 times per capture) at commit, so it was removed (see [performance.md](performance.md), round 4).

**Invariants enforced by the database**, not only by the code (see [V1__schema.sql](../src/main/resources/db/migration/V1__schema.sql)):
- **Append-only history.** Triggers reject `UPDATE`/`DELETE` on postings, entries, payments and refunds; corrections are new entries.
- **Fixed account identity.** On accounts, only `balance` may change.
- **Splits add up.** `CHECK`s require each payment's and refund's parts to sum to its amount.

**Atomicity.** A capture or refund is one database transaction: the business record (`payments`/`refunds`) and its journal entry commit together.

**Code layout.** `LedgerWriter` validates postings, caches existing account ids and coordinates ordered writes; `LedgerTable` contains SQL and row mapping. `LedgerBatchCommit` manages the queue, savepoints, commit and retry. These components know nothing about payment rules. The domain mapping lives in [`ChartOfAccounts.kt`](../src/main/kotlin/io/github/refined/morledger/ledger/ChartOfAccounts.kt), the money math in [`money/Allocation.kt`](../src/main/kotlin/io/github/refined/morledger/money/Allocation.kt).

## SQL access

Stateless `internal object *Table` components contain SQL and row mapping. `PaymentsTable` and `RefundsTable` return stored records; `PaymentService` assembles `PaymentDetails` and the named `PaymentPage`. A Table can query several related physical tables: `LedgerTable` handles the ledger schema, and `AnalyticsTable` handles business-table aggregates. `Analytics` assembles reports, fills time-series gaps and reconciles merchant balances with the ledger.

`AnalyticsTable.paymentFlows` and `refundFlows` use separate queries so their source tables, amount columns and event-time filters are visible in SQL. Refund apportionment lives directly in `Allocation.allocateRefund`, including the largest-remainder calculation and merchant → fee → tax tie-break.

The small JDBC helper in `db/Sql.kt` keeps statements explicit without a new dependency:

```kotlin
val query = sql {
    """
    SELECT payment_id
    FROM payments
    WHERE ${where {
        if (merchantId != null) add("merchant_id = ${param(merchantId)}")
    }}
    """
}
val ids = conn.query(query) { it.getString("payment_id") }
```

`param` returns `?` and records the value in order; it never escapes or inserts the value into SQL. Write queries as multiline SQL with each clause on its own line, including subqueries. Optional conditions live inline at `WHERE ${where { ... }}`: the small `where` helper joins them with `AND`, or returns `TRUE` for an empty list. Conditions register parameters in their SQL order. Ordinary interpolation is reserved for static SQL fragments and enum-controlled identifiers or sort directions. Do not concatenate completed `Sql` objects: their parameter lists must stay attached to their text. JDBC helpers close statements and result sets, bind nullable values and convert `Instant` to UTC. Batch inserts and balance updates reuse one prepared statement with `executeBatch`; balance updates keep account-id order. Transactions remain explicit at the service or batch boundary.

## Balance modes

`BALANCE_MODE` decides how captures and refunds are written. Balances are exact in both modes, and a test runs the same money flow through both and compares the reports.

| Mode | Per request | Commits | Balance updates |
|---|---|---|---|
| `STORED` (default) | One transaction: business row, journal entry, `UPDATE accounts SET balance = balance + x` on each account | One per request | One per account per request; the row lock is held until commit |
| `BATCHED` | Queued; one writer thread runs a batch of up to `BATCH_MAX_SIZE` requests in one transaction | One per batch | One per account per batch, with the summed delta |

**Group commit**, `LedgerBatchCommit` ([`ledger/LedgerBatchCommit.kt`](../src/main/kotlin/io/github/refined/morledger/ledger/LedgerBatchCommit.kt)):
- **Validation runs outside.** Parsing, validation, the fingerprint and the split run on the request thread, in parallel. Only the database work is queued.
- **The batch fills by itself under load.** The writer takes the first queued request plus whatever else is waiting, up to `BATCH_MAX_SIZE`, or keeps waiting up to `BATCH_MAX_WAIT_MS` (default 0: no artificial delay). At low load a batch holds one request, so latency is unchanged; near saturation batches grow and commits get rarer. A batch is whatever is in the queue, in arrival order: requests for any merchants and accounts mixed.
- **One request fails alone.** Each request runs under its own `SAVEPOINT`. A refused refund or an idempotency conflict rolls back to it and answers with its error; the rest of the batch commits. Its balance changes are merged into the batch only on success.
- **Each request is answered after its batch has committed**, so a 201 means the money is durably booked, as in `STORED`.
- **If the commit itself fails,** each request in the batch is retried in its own transaction, so one bad request cannot fail its neighbours.
- **What it costs:** one writer means one connection's worth of database work at a time. Idempotency and refund locking are unchanged: a duplicate `paymentId` in the same batch hits the same `ON CONFLICT` and fingerprint check.

Four more variants were measured and removed (the numbers are in [performance.md](performance.md)):
- balances summed on read;
- a periodic snapshot read with or without the tail since the refresh;
- hot accounts split into sub-accounts;
- TigerBeetle.

What integrating TigerBeetle taught us (dual write, linked-transfer retries, static memory, io_uring) is in [ledger-comparison.md](ledger-comparison.md).

## Analytics and the console

The console's per-merchant breakdown, time series and money-flow diagram come from `/v1/analytics/*`. These aggregate the business records (`payments`, `refunds`), which carry the full split of every event.
- **Cross-check.** Each merchant row carries a `reconciled` flag: the balance computed from the records must equal the merchant's balance in the ledger.
- **Ledger page.** `/v1/ledger/accounts` lists every account, and `/v1/ledger/verify` checks that each stored balance equals the sum of its postings.

**Demo data.** [`perf/k6/seed.js`](../perf/k6/seed.js) generates the demo data deterministically from `SEED`:
- ~100 Zipf-sized merchants, a few of them refund-heavy;
- ~100k payments over a year, in 3 currencies and 9 jurisdictions;
- ~7% refunds, 30% of them partial.

It goes through the public API, so it exercises the same code paths as real traffic, and it is idempotent.

## Assumptions

Where the brief is open, these are the decisions and why.

| # | Question | Decision | Why |
|---|---|---|---|
| 1 | What does the capture carry? | The **gross amount the customer paid, tax included**, plus `taxRate`; tax = gross × r / (1 + r) | A PSP capture event reports the charged amount; EU consumer prices are tax-inclusive |
| 2 | Fee base | **% of gross** (`MOR_FEE_RATE`, default 5%) | Industry practice (Paddle, Lemon Squeezy); the MoR's own processing cost is also charged on gross |
| 3 | Fee on refund | `REFUND_FEE_POLICY`: **`MOR_KEEPS_FEE`** (default) or `MOR_RETURNS_FEE` | Most processors and MoRs keep their fee on refunds; some contracts differ, so it is a policy |
| 4 | When do terms apply? | Fee rate and refund policy are **snapshotted on each payment at capture** | Changing config must not rewrite the economics of past sales |
| 5 | Rounding | Tax and fee **HALF_UP** to minor units; the merchant gets the exact remainder | Commercial rounding, as on invoices; the entry always balances |
| 6 | Partial refunds | Apportioned across **what remains** of tax / fee / merchant by **largest remainder**; the refund that empties the payment takes exactly what is left. Ties favour merchant → fee → tax | No drift and no negative parts, proven by a property test; on a tie, the cent never reduces the tax owed |
| 7 | Idempotency | `paymentId` / `refundId` are the **external event ids**; a hash of the request tells a replay (200, original body) from a conflict (409) | Matches webhook retry semantics without a separate `Idempotency-Key` header |
| 8 | Refund amount | Optional; **omitted = full refund of what remains** | The full refund is the main flow |
| 9 | Negative merchant balance | **Allowed** and reported | A kept fee or a refund after payout means the merchant owes the MoR; recovery happens in payouts (out of scope) |
| 10 | Tax authorities | `taxJurisdiction` is **required** on capture (`NL`, `DE`, `US-CA`); one payable account per jurisdiction and currency | "Owed to tax authorities" is plural; the MoR remits per authority |
| 11 | Currencies | **Multi-currency, no FX**: accounts are per (code, currency) and the report groups by currency | Converting would need rates and an FX policy; that belongs at settlement or payout |
| 12 | Time | `capturedAt` / `refundedAt` are the **effective** (event) time and `asOf` filters on it; `recordedAt` is kept too. Event times in the future are rejected, and a refund cannot predate its capture | Reports should follow when things happened, not when we heard of them |
| 13 | Unknown fields | **Rejected (400)** | A typo in an optional field (`"ammount"` on a refund) must not silently become a full refund |
| 14 | Merchants | No registry: a merchant exists once it has a payment | Onboarding is out of scope |

## Trade-offs

**Own ledger on Postgres vs TigerBeetle vs Formance.** The decisive argument is atomicity with the business record: the payment row, its idempotency fingerprint and its journal entry commit in one transaction.

- **TigerBeetle**
  - What it offers: very high throughput, strict double-entry semantics, linked transfers, and balance limits (it could enforce "refunds ≤ capture" on its own).
  - What it costs: payment metadata still needs a database, so every capture becomes a dual write between that database and TigerBeetle. That needs an outbox or a reconciliation job. One more stateful process to run. Our load is far below where it pays off.
  - Measured: we integrated it and benchmarked it under the same 2 CPU / 3 GB budget. Its ~2.3 GB of static memory starved the app and Postgres, and it held no step, not even 200 rps ([performance.md](performance.md)). It was then removed.
- **Formance Ledger**
  - What it offers: a ready ledger API, Numscript for splits (including rounding allotments), and built-in audit history.
  - What it costs: a separate service to run, the same dual-write problem, and the domain rules would live in Numscript instead of tested Kotlin.
- **Our engine** is small and sits behind the ledger package that knows nothing about payments, so it could be swapped. It also lets the database enforce the invariants itself (append-only triggers, fixed account identity).

**Running balances, updated synchronously.**
- **Chosen:** the stored balance changes in the same transaction as its postings.
  - Reads are O(1).
  - There is no lag.
  - The balance is exact at the moment a decision is made.
- **Rejected: recompute on read** (`SUM` over all postings). Fine at small scale, but O(postings) per report: 5–10 s at 100k payments.
- **Rejected: periodic snapshots + tail sum.** Exact and lock-free on write, but every read pays for the tail; under a mixed load it halved the holding rate.
- **Rejected: snapshot only.** Constant-cost reads, but stale by up to one refresh, and writes get no cheaper: each request still commits on its own.
- **Kept as an option: group commit (`BATCHED`).** Still synchronous and exact, but a batch of requests shares one commit and one balance update per account.
- **Rejected: updating balances from a queue** (outbox → broker → consumer). The balance lags, so it cannot back decisions such as "can we refund / pay out X?". It also needs an outbox, idempotent consumers and per-account ordering. A queue is the right tool for what happens *around* the ledger (merchant webhooks, notifications, analytics), not for the balance itself.

The cost of the chosen approach is the **hot account**. `psp_clearing` and `fee_revenue` are touched by every payment, so their row lock serialises those updates (see Concurrency below). Measured on 2 CPUs, that lock is `STORED`'s limit once requests run concurrently: in the one-merchant scenario `STORED` holds 600 rps, with 64–69% of active Postgres backends waiting on a lock. Group commit (`BATCHED`) takes each balance row once per batch and holds ~1000 rps in every scenario (round 4 in performance.md). The remedies, in the order to reach for them:
- `BALANCE_MODE=BATCHED` (in the code): group commit, one commit and one balance update per account per batch;
- splitting a hot account into N sub-accounts (measured before the request-thread fix, then removed; worth revisiting);
- moving the engine to TigerBeetle, which avoids locks by applying transfers sequentially in a single thread.

**Natural-key idempotency instead of an `Idempotency-Key` header.**
- It fits webhooks, where the event id is the key.
- It relies on the sender keeping ids stable.
- Fingerprints cover the request, not the response, so a replay returns the *current* state of the payment. For example, after a later refund the replay shows its refunded totals. The split itself never changes.

**Effective-time reports.**
- A late-arriving event changes a past `asOf` report.
- A real finance system would add bitemporal queries (`asOf` effective time *and* "as known at" recording time) and closed periods.
- `recordedAt` is stored so that this can be added later.

**Fee policy as config, not per merchant.** One global setting, snapshotted per payment. Per-merchant pricing would move these settings to a merchant contract table, and the snapshot logic would stay the same.

**Concurrency: database row locks, no explicit locking code.**
- **Balances.** `UPDATE … SET balance = balance + x` is atomic, and Postgres row-locks the account until commit. Two transactions posting to `fee_revenue` at once therefore queue up: the second waits for the first to commit, re-reads the row and adds its amount. Nothing is lost, and no `SELECT … FOR UPDATE` or retry loop is needed.
  - To keep the locks short, the balance updates are the last statements of the transaction.
  - To prevent deadlocks, accounts are always locked in ascending id order.
- **Refunds.** A refund is a read-check-write: read what is still refundable, check the amount, insert. So it takes `SELECT … FOR UPDATE` on its payment row, which serialises refunds *of the same payment* only.
- **Captures** race only on the primary key: `INSERT … ON CONFLICT DO NOTHING`, then compare fingerprints.
- **Tests.** 16 parallel captures on shared accounts lose nothing and do not deadlock; 20 racing partial refunds never over-refund.

Alternatives considered:
- **Optimistic locking** (a version column; retry on conflict). Good when conflicts are rare. On hot accounts that every payment touches, it turns into a retry storm.
- **Locks in application code** (a mutex or per-account queue). Correct only while there is a single instance. With more instances it needs a distributed lock (e.g. Redis), which adds a new failure mode: a lock that expires while its transaction is still running. The database row lock is already a distributed lock, and it is released on commit, rollback and crash.
- **For refunds: `SERIALIZABLE` with retries.** Correct, but needs retry plumbing.
- **For refunds: a `refunded_amount` column with `CHECK (refunded_amount <= amount)`.** The payment would then no longer be append-only.
- **Future payouts ("not more than the balance")** need no explicit lock either. A conditional update does the check and the write in one atomic step: `UPDATE accounts SET balance = balance - x WHERE id = ? AND balance >= x`, where 0 rows updated means insufficient funds.

**Strictness over convenience.**
- Unknown JSON fields are rejected.
- Amounts must be integers.
- Rates must be decimal strings (no floats anywhere).

**Out of scope**, with the postings each would add:

| Feature | Postings |
|---|---|
| Settlement from the PSP | Dr `bank` + Dr `psp_fee_expense` / Cr `psp_clearing` |
| Merchant payouts | Dr `merchant_payable` / Cr `bank`; a negative balance is netted against future payouts or a reserve |
| Tax remittance | Dr `tax_payable:{j}` / Cr `bank`, per filing period |
| Chargebacks | Like a refund, plus a dispute fee and a possible reversal on win |
| FX | Conversion entries through an FX account at settlement or payout |
| Other | Invoicing and credit notes, authentication, multi-tenant isolation, rate limiting |

## Tests and CI

```bash
docker compose --profile test run --rm test      # Docker only: both suites
gradle test                                      # without Docker: unit tests only (JDK 17+, Gradle 9)
gradle check                                     # without Docker: both suites (Testcontainers needs a Docker daemon)
```

Two Gradle test suites ([JVM Test Suite plugin](https://docs.gradle.org/current/userguide/jvm_test_suite_plugin.html)):

| Suite | Folder | What | Rule |
|---|---|---|---|
| `test` | `src/test/kotlin` | Unit tests of pure functions: money math (with a seeded property test over 10,000 random refund sequences), journal-entry validation, report grouping, config parsing, SQL parameter construction | No database, no app, no setup beyond constructing values |
| `integrationTest` | `src/integrationTest/kotlin` | The service through its HTTP API (Ktor `testApplication`) against a real Postgres | Everything with state is tested here, through the API |

The integration suite covers:
- **SQL parameters**: HTTP reads with quotes and SQL syntax in identifiers/search remain literal filters; normal filter combinations and pagination retain their totals and ordering. Unit tests cover parameter order, repetition, nulls and optional conditions.
- **API flows**: both fee policies, partial refunds, idempotent replays and conflicts, guards, multi-currency, `asOf`, analytics, validation.
- **Concurrency**: parallel HTTP requests (the test asserts they really overlapped): 16 captures on shared accounts (no lost updates, no deadlocks), 20 racing partial refunds, duplicate captures, replayed refunds.
- **Balance modes**: the same flow through the API in `STORED` and `BATCHED` gives identical reports; in a batch, a conflicting capture and an over-refund fail alone while their neighbours commit; concurrent batched captures lose nothing.
- **Database invariants**: data goes in through the API, then raw SQL tries to go around it: history is append-only, and an unbalanced posting or a drifted stored balance shows up in `/v1/ledger/verify`.
- **Ledger level** (`ledger/`, the only test below the API): `LedgerBatchCommit` with a batch composed on purpose: 20 requests, one failing after it wrote its entry; the other 19 commit in fewer than 20 batches, the failed one leaves nothing behind.

Tests run against a real Postgres:
- in the compose `test` service, a throwaway `postgres-test` (tmpfs);
- with `gradle check` on the host, one started by Testcontainers;
- otherwise, any dedicated database set via `TEST_DB_URL` (+ `TEST_DB_USER`, `TEST_DB_PASSWORD`). The tests **drop and recreate its schema**, so never point it at data you care about.

[CI](../.github/workflows/ci.yml) runs the same path as the README: it builds the image, brings up compose, runs an API smoke test (capture → refund → balanced report) and the test suite in Docker. Because everything runs in Linux containers, the result is the same on macOS, Windows and Linux hosts with Docker.

## Configuration

docker compose reads an optional **`.env`** next to `docker-compose.yml`. [`.env.example`](../.env.example) lists every setting with its default, so the stack runs without one. To change something:

```bash
cp .env.example .env     # then edit; .env is gitignored
docker compose up -d --build --wait
```

| `.env` variable | Default | |
|---|---|---|
| `APP_HOST_PORT` | `8090` | Host port of the service (bound to 127.0.0.1) |
| `POSTGRES_HOST_PORT` | `5442` | Host port of Postgres, for psql/IDE access |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `mor_ledger` / `mor` / `mor` | Passed to the app as `DB_URL` / `DB_USER` / `DB_PASSWORD` |
| `MOR_FEE_RATE` | `0.05` | MoR fee as a fraction of gross, `[0, 0.5]` |
| `REFUND_FEE_POLICY` | `MOR_KEEPS_FEE` | or `MOR_RETURNS_FEE` |

The default ports (8090, 5442) are deliberately not 8080/5432, to avoid clashing with a local Postgres or another service. When running the service from the host (`gradle run`), it reads `PORT`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `MOR_FEE_RATE` and `REFUND_FEE_POLICY` from the environment, with the same defaults.

## Stack

Kotlin 2.4 (bytecode for JVM 17+), Ktor 3, PostgreSQL 17 via plain JDBC (HikariCP), Flyway, kotlinx.serialization; JUnit 6 + Testcontainers; Gradle 9, from the official `gradle:9.8.1-jdk21` image, so no wrapper and no local Gradle are needed. Docker: a multi-stage image (JDK 21 build, JRE 21 runtime as a non-root user, healthcheck on `/health`) and Docker Compose for the stack.

There is deliberately no DI framework and no ORM: in a ledger, the SQL and the transaction boundaries are the interesting part, so they stay explicit. The demo console is vanilla JS with no build step.

```
docker-compose.yml             the whole stack: postgres, app, test profile
.env.example                   optional settings (ports, DB credentials, pricing)
Dockerfile                     image build: gradle:9.8.1-jdk21 -> JRE 21
build.gradle.kts               Gradle build (no wrapper; versions inline)
docs/                          requirements, design notes, demo.http, domain background
src/main/kotlin/io/github/refined/morledger/
  money/Allocation.kt          capture split, refund apportionment (pure functions)
  ledger/Model.kt              ledger models and pure posting validation
  ledger/LedgerWriter.kt       account cache and posting coordination
  ledger/LedgerTable.kt        ledger SQL and row mapping
  ledger/LedgerBatchCommit.kt  queue, savepoints, group commit, retry
  ledger/ChartOfAccounts.kt    accounts and the postings for capture/refund
  payments/                    PaymentService (transactions, idempotency, locking), PaymentsTable, RefundsTable, model
  reports/BalanceReport.kt     ledger balances -> report
  reports/Analytics.kt         report assembly, gap filling, reconciliation
  reports/AnalyticsTable.kt    SQL aggregates
  db/Sql.kt                    safe parameter interpolation and JDBC helpers
  api/                         routes, DTOs, error mapping
src/main/resources/
  db/migration/                Flyway: V1__schema.sql (ledger + business tables, constraints, triggers)
  web/                         demo console, Swagger page, openapi.yaml
src/test/kotlin/...            unit tests: pure functions only
src/integrationTest/kotlin/... tests through the HTTP API against real Postgres
```
