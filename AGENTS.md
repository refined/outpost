# AGENTS.md

Working context for AI coding agents (and humans) in this repo: what is decided, how to build, what is still open.

**Keep it current.** When a decision is made or changed, update this file in the same commit.

## Project

- **What:** a Mini Merchant of Record (MoR) ledger service with three APIs:
  - capture a payment;
  - refund a payment;
  - a balances report: tax owed to authorities, merchant balances.
- **Spec:** [docs/requirements.md](docs/requirements.md).
- **Target date:** 2026-10-19.
- **Status:** skeleton. `/health` works. Capture, refund and report are `501` stubs. The ledger schema is not written yet. CI runs on Linux, macOS and Windows.
- **Platforms:** must build, test and run on macOS, Linux and Windows.
- **What is judged:** reasoning and trade-offs as much as the code. Every ambiguity gets an explicit, documented decision.

## Stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin 2.4, JVM target 17 | Builds with any JDK 17+ |
| HTTP | Ktor 3 (Netty), kotlinx.serialization | |
| DB access | Plain JDBC + HikariCP | No ORM: SQL and transaction boundaries stay explicit |
| Migrations | Flyway (`src/main/resources/db/migration`) | |
| Database | PostgreSQL 17 (docker-compose) | |
| Tests | JUnit 6, Ktor `testApplication`, Testcontainers | Docker by default; or an external Postgres via `TEST_DB_URL` |
| CI | GitHub Actions, `.github/workflows/ci.yml` | Ubuntu (JDK 17 + 25), macOS, Windows |
| Build | Gradle 9 via wrapper, Kotlin DSL, version catalog `gradle/libs.versions.toml` | Gradle is not installed globally; always use `./gradlew` |

There is no DI framework: the app is wired by hand in `Application.module(dataSource)`. Keep dependencies minimal, and justify any new one.

## Layout

```
src/main/kotlin/io/github/refined/morledger/
  Application.kt        main(), AppConfig (env), Application.module()
  api/Routes.kt         HTTP routes
  db/Database.kt        Hikari + Flyway, DataSource.transaction { }
src/main/resources/
  db/migration/         Flyway migrations V{n}__*.sql
  logback.xml
src/test/kotlin/...     tests; TestDatabase = one shared, migrated Postgres per test JVM
.github/workflows/      CI
docs/requirements.md    what we build and why
docs/domain/            MoR background: VAT, money flows, ledger posting examples
```

## Commands

```bash
docker compose up -d --wait   # Postgres on 127.0.0.1:5442 (db/user/password: mor_ledger/mor/mor)
./gradlew build               # compile + tests
./gradlew test                # tests only; report: build/reports/tests/test/index.html
./gradlew run                 # service on http://localhost:8090
```

Environment variables: `PORT` (8090), `DB_URL` (`jdbc:postgresql://localhost:5442/mor_ledger`), `DB_USER`, `DB_PASSWORD`.

Tests use `TestDatabase`:
- if `TEST_DB_URL` (+ `TEST_DB_USER`, `TEST_DB_PASSWORD`) is set, they use that database;
- otherwise they start Postgres via Testcontainers.

Tests create tables, so point `TEST_DB_URL` at a dedicated database, not the dev one.

## CI

`.github/workflows/ci.yml` runs `./gradlew build` on push to `main`, on PRs and on manual dispatch. The matrix:
- Ubuntu with JDK 17 and JDK 25 (Testcontainers);
- macOS with JDK 17 (native Postgres via `ikalnytskyi/action-setup-postgres`);
- Windows with JDK 17 (native Postgres, run through `gradlew.bat`).

GitHub's macOS/Windows runners cannot run Linux containers, so they use native Postgres. On failure, the test reports are uploaded as artifacts.

Rules:
- Code must stay cross-platform: no shell-specific build steps; use `java.nio.file` paths, not string concatenation.
- `gradlew` must keep its executable bit (`git update-index --chmod=+x gradlew`); git on Windows drops it easily.
- A new test that needs the database uses `TestDatabase.dataSource`; never start a separate container per test class.

## Environment notes

- The development machine runs Windows. Git Bash and PowerShell are both available; in PowerShell, use `.\gradlew.bat`.
- Ports 5432 and 8080 are often taken by other local projects, which is why the defaults are 5442 and 8090. Windows reserves some high port ranges (e.g. 55389–55488), so don't pick ports there.
- `system-design/` is a local-only, gitignored folder. Never add it to git or link to it. Domain material that should be public lives in `docs/domain/`.
- Never push to the remote without explicit approval from the repo owner. The repo is public.

## Coding conventions

- All files, code, comments and commit messages are in English.
- **Money:**
  - amounts are `Long` minor units plus an ISO-4217 currency code, never `Double`/`Float`;
  - rates and percentages are `BigDecimal` (or basis points as `Long`);
  - rounding happens in one place, with a documented rule.
- **Ledger:**
  - append-only: never `UPDATE`/`DELETE` journal entries or postings; corrections are new entries;
  - every journal entry balances (Σ debits = Σ credits per currency), enforced in code and, where practical, in the DB;
  - balances are derived from postings.
- **Transactions:** one business operation is one DB transaction (`DataSource.transaction { }`). The business record (payment, refund) and its journal entry are written together.
- **Idempotency:** every mutating API must be safe to retry (an idempotency key or natural key such as the external payment id).
- **Migrations:** never edit an applied migration; add a new `V{n}__*.sql`.
- **Tests:** money flows are covered by integration tests against real Postgres.
- **Docs:** every ambiguity decision goes into the README "Assumptions" section; key trade-offs go into "Trade-offs".

## Domain model (draft)

**Chart of accounts (per currency).** "Normal" is the side on which the account's balance is positive.

| Account | Type | Normal | Meaning |
|---|---|---|---|
| `psp_clearing` | asset | debit | Money collected from customers, held at or receivable from the PSP |
| `tax_payable:{jurisdiction}` | liability | credit | Tax owed to an authority |
| `merchant_payable:{merchantId}` | liability | credit | What the MoR owes the merchant |
| `fee_revenue` | revenue | credit | The MoR's fee |

**Capture.** Gross G, tax T, fee F; one journal entry:

| Side | Account | Amount |
|---|---|---|
| Dr | `psp_clearing` | G |
| Cr | `tax_payable:{jurisdiction}` | T |
| Cr | `fee_revenue` | F |
| Cr | `merchant_payable:{merchantId}` | G − T − F |

**Refund.** A new entry that reverses the capture: the full amounts for a full refund, proportional amounts for a partial one. How the fee is treated is an open question (below).

**Report:**
- tax owed = the credit balance of each `tax_payable:*` account;
- merchant balance = the credit balance of each `merchant_payable:*` account.

See [docs/domain/design-options.md](docs/domain/design-options.md) (section D5) for a worked example with VAT.

## Ledger choice

**Our own double-entry ledger on Postgres.**

Why:
- the business record and its postings commit atomically in one transaction, with no dual write;
- the domain modelling stays visible;
- it is one container to run.

Alternatives to discuss in the README:
- **TigerBeetle:** strong guarantees, built for very high throughput, but the metadata store is separate (dual write) and there is one more process to run;
- **Formance:** a ready ledger API with Numscript, but a separate service to run, and less of our own modelling shows.

The ledger sits behind a small internal interface, so it could be swapped.

## Open questions (decide, then document in the README)

1. **Tax-inclusive or tax-exclusive amounts on capture.** Does the event carry the gross amount, or net plus rate?
2. **Fee base:** a % of net (excluding tax) or of gross.
3. **Fee on refund:** does the MoR return its fee (Dr `fee_revenue`), or does the merchant bear it?
4. **Rounding:** the rule (HALF_EVEN vs HALF_UP), and which line absorbs the remainder.
5. **Idempotency:** a client `Idempotency-Key` header vs an external payment/event id; what a duplicate with a different payload returns.
6. **Refund guards:** refunding more than was captured, a second full refund, a refund of an unknown payment.
7. **Negative merchant balance:** allowed, e.g. a refund after a payout.
8. **Tax jurisdiction:** sent on capture (country code) or one implicit authority.
9. **Fee configuration:** global config or per merchant.
10. **Currency:** single now; with multi-currency, balances are per currency and there is no FX.
11. **Concurrency:** lock the payment row on refund so that concurrent partial refunds can't exceed the captured amount.
12. **Time:** event timestamps vs recording timestamps, and reporting "as of" a date.
