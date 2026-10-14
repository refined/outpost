# Mini MoR Ledger Service

[![CI](https://github.com/refined/outpost/actions/workflows/ci.yml/badge.svg)](https://github.com/refined/outpost/actions/workflows/ci.yml)

A small ledger service for a **Merchant of Record (MoR)**. The MoR:
- collects payment from the customer;
- owes sales tax/VAT to the tax authority;
- keeps its fee;
- owes the remainder to the merchant.

The service records **captures** and **refunds** as balanced double-entry journal entries and reports **what the MoR owes tax authorities** and **each merchant's balance**.

- What we build and why: [docs/requirements.md](docs/requirements.md)
- Working notes and decisions log: [AGENTS.md](AGENTS.md)
- Domain background (MoR, EU VAT): [docs/domain/](docs/domain/)

## Quick start

Prerequisites: **Docker only**: no JDK or Gradle on the host. Works on macOS, Linux and Windows.

```bash
docker compose up -d --build --wait              # builds the image, starts Postgres + the service
docker compose --profile test run --rm test      # runs the full test suite in a container, against its own Postgres
docker compose --profile test down               # stop everything, incl. the test database (-v also drops data)
```

The first build downloads Gradle and the dependencies (a few minutes); later builds reuse the cache. Ports and settings can be changed in an optional `.env` (see [Configuration](#configuration)).

| URL | What |
|---|---|
| http://localhost:8090/ui/ | **Demo console**: capture, refund, run a scripted scenario, and watch the report, payments and journal update |
| http://localhost:8090/docs/ | Swagger UI over the [OpenAPI spec](service/src/main/resources/web/openapi.yaml) |
| [docs/demo.http](docs/demo.http) | The same scenario as plain HTTP requests (IntelliJ HTTP client / VS Code REST Client) |

**Working on the code without Docker for the app** (optional, needs JDK 17+): keep Postgres in Docker and run the service from the host. The Gradle project lives in [`service/`](service/), and Gradle itself comes via the wrapper.

```bash
docker compose up -d --wait postgres
cd service
./gradlew run                     # same URLs; Windows: gradlew.bat (cmd) or .\gradlew.bat (PowerShell)
./gradlew test                    # Testcontainers starts its own Postgres
```

## Where the difficulty is

Storing balanced postings and summing them is the easy part: a ledger *engine* is about a hundred lines plus a few constraints. An off-the-shelf engine (TigerBeetle, Formance) replaces only that. Everything below is domain work that stays ours whichever engine is used, and that is where this service spends its effort:

1. **Mapping events to postings.** Which accounts exist, what a refund reverses, who bears the fee, and the fact that a merchant can end up owing the MoR.
2. **Money math in minor units.**
   - Extracting tax from a tax-inclusive price and taking the fee, so that the parts add up exactly.
   - Splitting *partial* refunds so that a series of them never drifts by a cent and no part goes negative.
3. **Idempotency.** Captures and refunds arrive as PSP webhooks, and those are retried. A replay must not post twice; a reused id with a different payload is an error, not a silent overwrite.
4. **Concurrency.** Two partial refunds racing on one payment must not exceed what was captured.
5. **Invariants and audit.**
   - Every entry balances; the ledger is append-only.
   - Stored balances are kept in step with the postings, and can be reconciled against them.
   - Terms such as rates and policy are snapshotted on each payment.
6. **Time.** Event time vs recording time, "balances as of" a date, and refunds dated before their capture.

## API

Money is always an **integer number of minor units** plus an ISO 4217 currency; rates are **decimal strings** (`"0.21"` = 21%). Every error has one shape:

```json
{"error": "<stable code>", "message": "<human readable>"}
```

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/payments` | Capture a payment |
| `POST` | `/v1/payments/{paymentId}/refunds` | Refund it, fully (no `amount`) or partially |
| `GET` | `/v1/reports/balances?asOf=&currency=` | Tax owed per jurisdiction, merchant balances, MoR position, integrity check |
| `GET` | `/v1/payments/{paymentId}` | A payment with its refunds and what is still refundable |
| `GET` | `/v1/payments?limit=` | Recent payments |
| `GET` | `/v1/journal?paymentId=&limit=` | Journal entries with their debit and credit lines |
| `GET` | `/health` | Liveness and a database check |

### Capture

```http
POST /v1/payments
{"paymentId": "pay_1001", "merchantId": "acme", "amount": 12100, "currency": "EUR",
 "taxRate": "0.21", "taxJurisdiction": "NL", "capturedAt": "2026-10-14T10:00:00Z"}
```

`201` returns the payment with its split: `taxAmount: 2100, feeAmount: 605, merchantAmount: 9395`, plus the rates and fee policy that were applied. Re-sending the same request returns `200` with the same body. Re-using `pay_1001` with any other payload returns `409 idempotency_conflict`. `capturedAt` is optional (defaults to now).

### Refund

```http
POST /v1/payments/pay_1001/refunds
{"refundId": "ref_1001_a", "amount": 6050}
```

`201` returns `{refund, payment}`:
- the refund's split: `taxAmount: 1050, feeAmount: 0, merchantAmount: 5000`;
- the updated payment (`status: PARTIALLY_REFUNDED`, `refundable`).

Omitting `amount` refunds everything that remains. The errors:

| Status | Code | When |
|---|---|---|
| 404 | `payment_not_found` | Unknown payment |
| 422 | `refund_exceeds_refundable` | The amount is more than what remains |
| 422 | `payment_fully_refunded` | Nothing remains to refund |
| 422 | `refund_before_capture` | `refundedAt` is before `capturedAt` |
| 409 | `idempotency_conflict` | `refundId` reused with another payload |

### Balances report

```http
GET /v1/reports/balances
```
```json
{
  "asOf": null,
  "taxAuthorities": [{"jurisdiction": "NL", "currency": "EUR", "amountOwed": 1050}],
  "merchants":      [{"merchantId": "acme", "currency": "EUR", "balance": 4395}],
  "mor":            [{"currency": "EUR", "feeRevenue": 605, "pspClearing": 6050}],
  "integrity":      {"balanced": true, "unbalancedCurrencies": []}
}
```

- **`taxAuthorities`**: what the MoR owes each tax authority.
- **`merchants`**: what the MoR owes each merchant. A **negative** balance means the merchant owes the MoR.
- **`mor`**: the MoR's own position (fee revenue, money held at the PSP).
- **`integrity`**: proves the ledger sums to zero in every currency.
- **`asOf`** (optional) asks for balances at a past event time; they are summed from the postings. Without it, the report reads the stored running balances.

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
- `Ledger.verify` reconciles stored balances against the postings, and the tests run it after the money flows.

This is the model the established ledgers use: TigerBeetle keeps debit/credit counters on each account; Modern Treasury keeps balances on the account with a lock version.

**Invariants enforced by the database**, not only by the code (see [V1__schema.sql](service/src/main/resources/db/migration/V1__schema.sql)):
- **Balanced, single-currency entries.** A deferred constraint trigger checks that every journal entry balances and uses a single currency, at commit time.
- **Append-only history.** Triggers reject `UPDATE`/`DELETE` on postings, entries, payments and refunds; corrections are new entries.
- **Fixed account identity.** On accounts, only `balance` may change.
- **Splits add up.** `CHECK`s require each payment's and refund's parts to sum to its amount.

**Atomicity.** A capture or refund is one database transaction: the business record (`payments`/`refunds`) and its journal entry commit together.

**Code layout.** The ledger engine ([`ledger/Ledger.kt`](service/src/main/kotlin/io/github/refined/morledger/ledger/Ledger.kt)) knows nothing about payments. The domain mapping lives in [`ChartOfAccounts.kt`](service/src/main/kotlin/io/github/refined/morledger/ledger/ChartOfAccounts.kt), the money math in [`money/Allocation.kt`](service/src/main/kotlin/io/github/refined/morledger/money/Allocation.kt).

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
- **Formance Ledger**
  - What it offers: a ready ledger API, Numscript for splits (including rounding allotments), and built-in audit history.
  - What it costs: a separate service to run, the same dual-write problem, and the domain rules would live in Numscript instead of tested Kotlin.
- **Our engine** is small and sits behind one module (`Ledger`) that knows nothing about payments, so it could be swapped. It also lets the database enforce the invariants itself (deferred balance check, append-only triggers).

**Running balances, updated synchronously.**
- **Chosen:** the stored balance changes in the same transaction as its postings.
  - Reads are O(1).
  - There is no lag.
  - The balance is exact at the moment a decision is made.
- **Rejected: recompute on read** (`SUM` over all postings). Fine at small scale, but O(postings) per report.
- **Rejected: periodic snapshots + tail sum.** Exact and lock-free on write, but every read pays for the tail, and a background job is needed.
- **Rejected: updating balances from a queue** (outbox → broker → consumer). The balance lags, so it cannot back decisions such as "can we refund / pay out X?". It also needs an outbox, idempotent consumers and per-account ordering. A queue is the right tool for what happens *around* the ledger (merchant webhooks, notifications, analytics), not for the balance itself.

The cost of the chosen approach is the **hot account**. `psp_clearing` and `fee_revenue` are touched by every payment, so their row lock serialises those updates (see Concurrency below). At this service's scale, single-digit to low hundreds of TPS with millisecond transactions, that is fine. Beyond that, the known remedies are:
- splitting a hot account into N sub-accounts (`fee_revenue#0..N`, summed on read);
- micro-batching many transfers into one transaction, as TigerBeetle does;
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
docker compose --profile test run --rm test      # Docker only
cd service && ./gradlew test                     # with a local JDK
```

What the suite covers:
- **Money math**: unit tests plus a seeded property test over 10,000 random payments and refund sequences, checking no drift, no negative parts and an exact final refund.
- **Ledger**: balances; stored running balances equal the sum of postings (`Ledger.verify`), and drift is detected; database-level rejection of unbalanced entries; append-only history; accounts may change only their balance.
- **API flows**: both fee policies, partial refunds, idempotent replays and conflicts, guards, multi-currency, `asOf`, and validation.
- **Concurrency**: 16 parallel captures on shared accounts (no lost updates, no deadlocks), 20 racing partial refunds, duplicate captures, replayed refunds.

Tests run against a real Postgres:
- in the compose `test` service, a throwaway `postgres-test` (tmpfs);
- with `./gradlew test`, one started by Testcontainers;
- otherwise, any dedicated database set via `TEST_DB_URL` (+ `TEST_DB_USER`, `TEST_DB_PASSWORD`). The tests **drop and recreate its schema**, so never point it at data you care about.

[CI](.github/workflows/ci.yml) runs two paths:
- **Docker:** builds the image, brings up compose, runs an API smoke test (capture → refund → balanced report) and the test suite in Docker.
- **Gradle:** on Ubuntu (JDK 17 and 25, Testcontainers), macOS and Windows (JDK 17, native Postgres, `gradlew.bat` on Windows).

## Configuration

docker compose reads an optional **`.env`** next to `docker-compose.yml`. [`.env.example`](.env.example) lists every setting with its default, so the stack runs without one. To change something:

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

The default ports (8090, 5442) are deliberately not 8080/5432, to avoid clashing with a local Postgres or another service. When running the service from the host (`./gradlew run`), it reads `PORT`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `MOR_FEE_RATE` and `REFUND_FEE_POLICY` from the environment, with the same defaults.

## Stack

Kotlin 2.4 (bytecode for JVM 17+), Ktor 3, PostgreSQL 17 via plain JDBC (HikariCP), Flyway, kotlinx.serialization; JUnit 6 + Testcontainers; Gradle 9 via the wrapper. Docker: a multi-stage image (JDK 21 build, JRE 21 runtime as a non-root user, healthcheck on `/health`) and Docker Compose for the stack.

There is deliberately no DI framework and no ORM: in a ledger, the SQL and the transaction boundaries are the interesting part, so they stay explicit. The demo console is vanilla JS with no build step.

```
docker-compose.yml             the whole stack: postgres, app, test profile
.env.example                   optional settings (ports, DB credentials, pricing)
docs/                          requirements, demo.http, domain background
service/                       the Kotlin service (Gradle project + Dockerfile)
  src/main/kotlin/io/github/refined/morledger/
    money/Allocation.kt        capture split, refund apportionment (pure functions)
    ledger/Ledger.kt           double-entry engine: post (+ running balances), balances, verify, entries
    ledger/ChartOfAccounts.kt  accounts and the postings for capture/refund
    payments/                  PaymentService (transactions, idempotency, locking), repository, model
    reports/BalanceReport.kt   ledger balances -> report
    api/                       routes, DTOs, error mapping
  src/main/resources/
    db/migration/              Flyway: V1__schema.sql (ledger + business tables, constraints, triggers)
    web/                       demo console, Swagger page, openapi.yaml
  src/test/kotlin/...          unit, integration and concurrency tests
```
