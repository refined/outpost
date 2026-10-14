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
- **Status:** feature-complete for the brief, including the nice-to-haves (partial refunds, multi-currency) and extras (`asOf` reports, journal endpoint, demo console `/ui`, Swagger `/docs`). 43 tests; CI runs on Linux, macOS and Windows. What remains is polish and interview prep.
- **Platforms:** must build, test and run on macOS, Linux and Windows.
- **What is judged:** reasoning and trade-offs as much as the code. Every ambiguity gets an explicit, documented decision.

## Stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin 2.4, JVM target 17 | Builds with any JDK 17+ |
| HTTP | Ktor 3 (Netty), kotlinx.serialization | |
| DB access | Plain JDBC + HikariCP | No ORM: SQL and transaction boundaries stay explicit |
| Migrations | Flyway (`service/src/main/resources/db/migration`) | |
| Database | PostgreSQL 17 (docker-compose) | |
| Tests | JUnit 6, Ktor `testApplication`, Testcontainers | Docker by default; or an external Postgres via `TEST_DB_URL` |
| CI | GitHub Actions, `.github/workflows/ci.yml` | Gradle on Ubuntu (JDK 17 + 25), macOS, Windows; plus a docker compose job |
| Runtime | Docker: `service/Dockerfile` (multi-stage) + root `docker-compose.yml` + optional `.env` (`.env.example`) | Host needs only Docker |
| Build | Gradle 9 via wrapper, Kotlin DSL, version catalog `service/gradle/libs.versions.toml` | The Gradle project is `service/`; Gradle is not installed globally, so use `./gradlew` from `service/` |

There is no DI framework: the app is wired by hand in `Application.module(dataSource, pricing, clock)`. Keep dependencies minimal, and justify any new one.

## Layout

The root holds only what is needed to run and read the project; the Gradle project lives in `service/`. Keep the root that way.

```
docker-compose.yml               the whole stack (postgres, app, test profile); settings from optional .env
.env.example                     every compose setting with its default (.env is gitignored)
README.md, AGENTS.md, CLAUDE.md  docs for reviewers / agents (CLAUDE.md just imports this file)
docs/requirements.md             what we build and why
docs/demo.http                   demo scenario as HTTP requests
docs/domain/                     MoR background: VAT, money flows, ledger posting examples
.github/workflows/ci.yml         CI
service/                         Gradle project: build.gradle.kts, settings, wrapper, Dockerfile, .dockerignore, .editorconfig
  src/main/kotlin/io/github/refined/morledger/
    Application.kt               main(), AppConfig (env), Application.module()
    money/Allocation.kt          pure money math: capture split, refund apportionment, RefundFeePolicy
    ledger/Ledger.kt             generic double-entry engine (knows nothing about payments); running balances, verify
    ledger/ChartOfAccounts.kt    account codes + postings for capture/refund
    payments/                    PaymentService (tx, idempotency, locking), PaymentRepository (SQL), Model (+ DomainException types)
    reports/BalanceReport.kt     ledger balances -> report
    api/                         Routes, Dto (wire format), Errors (DomainException -> HTTP status)
    db/Database.kt               Hikari + Flyway, DataSource.transaction { }
  src/main/resources/
    db/migration/                V1__schema.sql: the only migration (edit in place while there are no users; wipe the pgdata volume after changes)
    web/                         ui/ (demo console, vanilla JS), docs/ (Swagger page), openapi.yaml
  src/test/kotlin/...            tests; TestDatabase = one shared, migrated Postgres per test JVM
```

## Commands

**Everything runs in Docker** (user requirement: the host needs only Docker). Multi-stage `Dockerfile`: `build` stage = JDK 21 + Gradle wrapper; runtime = JRE 21, non-root user, healthcheck on `/health`.

```bash
docker compose up -d --build --wait              # Postgres (127.0.0.1:5442) + app (127.0.0.1:8090)
docker compose --profile test run --rm test      # test suite in the build stage, against postgres-test (tmpfs)
docker compose logs -f app
docker compose --profile test down               # stop everything incl. postgres-test; -v also drops pgdata
```

Host-JDK workflow (optional, for fast edit-run loops):

```bash
docker compose up -d --wait postgres
cd service
./gradlew build               # compile + tests (Testcontainers)
./gradlew run                 # service on http://localhost:8090 (stop the app container first: same port)
```

Environment variables: `PORT` (8090), `DB_URL` (`jdbc:postgresql://localhost:5442/mor_ledger`), `DB_USER`, `DB_PASSWORD`, `MOR_FEE_RATE` (0.05), `REFUND_FEE_POLICY` (`MOR_KEEPS_FEE` | `MOR_RETURNS_FEE`).

URLs: `/ui/` demo console, `/docs/` Swagger, `/openapi.yaml` spec. **Keep `openapi.yaml` in sync with `api/Dto.kt` and `api/Routes.kt`.**

Tests use `TestDatabase`:
- if `TEST_DB_URL` (+ `TEST_DB_USER`, `TEST_DB_PASSWORD`) is set, they use that database;
- otherwise they start Postgres via Testcontainers.

Tests create and **truncate** tables (`TestDatabase.reset()`), so point `TEST_DB_URL` at a dedicated database, never the dev one.

## CI

`.github/workflows/ci.yml` runs `./gradlew build` (in `service/`) on push to `main`, on PRs and on manual dispatch. The matrix:
- Ubuntu with JDK 17 and JDK 25 (Testcontainers);
- macOS with JDK 17 (native Postgres via `ikalnytskyi/action-setup-postgres`);
- Windows with JDK 17 (native Postgres, run through `gradlew.bat`).

GitHub's macOS/Windows runners cannot run Linux containers, so they use native Postgres. On failure, the test reports are uploaded as artifacts.

Rules:
- Code must stay cross-platform: no shell-specific build steps; use `java.nio.file` paths, not string concatenation.
- `service/gradlew` must keep its executable bit (`git update-index --chmod=+x service/gradlew`); git on Windows drops it easily.
- A new test that needs the database uses `TestDatabase.dataSource`; never start a separate container per test class.

## Environment notes

- The development machine runs Windows. Git Bash and PowerShell are both available; in PowerShell, use `.\gradlew.bat`.
- Ports 5432 and 8080 are often taken by other local projects, which is why the defaults are 5442 and 8090. Windows reserves some high port ranges (e.g. 55389–55488), so don't pick ports there.
- `system-design/` is a local-only, gitignored folder. Never add it to git or link to it. Domain material that should be public lives in `docs/domain/`.
- The repo is public. Pushing straight to `main` is fine for now; never force-push. Keep CI green: run the tests (`docker compose --profile test run --rm test`, or `./gradlew build` in `service/`) before pushing.
- Prefer the Docker workflow. If you use `./gradlew run` from a background shell, killing the shell leaves the JVM running (Windows); free port 8090 (stop the `java.exe` owning it) before restarting, or you will be testing the old build.
- After changing code, `docker compose up -d --build --wait` rebuilds the image; without `--build` the old image keeps running.

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

## Domain model

The README is the reviewer-facing source of truth (worked example, Assumptions, Trade-offs). This is the short version.

**Chart of accounts (per currency).** Signed postings: debit > 0, credit < 0.

| Account | Kind | Normal |
|---|---|---|
| `psp_clearing` | asset | debit |
| `tax_payable:{jurisdiction}` | liability | credit |
| `merchant_payable:{merchantId}` | liability | credit |
| `fee_revenue` | revenue | credit |

- **Capture** (gross G = tax T + fee F + merchant M): Dr clearing G / Cr tax T, Cr fee F, Cr merchant M.
- **Refund r:** the mirror image for the refunded parts (`ChartOfAccounts.refund`).
- **Balances:** each account stores a running `balance`, moved in the same transaction as its postings. The current report reads it; `asOf` reports sum the postings.
- **Report:** tax owed and merchant balances are the credit balances of their accounts.

## Decisions (resolved 2026-10-14)

| # | Topic | Decision |
|---|---|---|
| 1 | Capture amount | Gross paid by the customer, **tax-inclusive**, plus `taxRate`; tax = G·r/(1+r) |
| 2 | Fee base | % of **gross**, `MOR_FEE_RATE` (default 0.05, max 0.5) |
| 3 | Fee on refund | `REFUND_FEE_POLICY`: `MOR_KEEPS_FEE` (default; the merchant bears it) or `MOR_RETURNS_FEE` |
| 4 | Terms snapshot | Fee rate and policy are stored on each payment at capture; config changes never affect past payments |
| 5 | Rounding | HALF_UP for tax and fee; the merchant gets the remainder |
| 6 | Partial refunds | Largest-remainder apportionment over what is still refundable; the last refund takes exactly the rest; ties merchant → fee → tax |
| 7 | Idempotency | External ids (`paymentId`, `refundId`) + a SHA-256 request fingerprint: replay → 200 with the stored result, different payload → 409 |
| 8 | Refund guards | 404 unknown payment; 422 `refund_exceeds_refundable`, `payment_fully_refunded`, `refund_before_capture` |
| 9 | Negative merchant balance | Allowed and reported |
| 10 | Jurisdiction | Required on capture; ISO 3166 (`NL`, `US-CA`) |
| 11 | Currency | Multi-currency, no FX; accounts per (code, currency) |
| 12 | Concurrency | Refund = `SELECT … FOR UPDATE` on the payment; capture = `INSERT … ON CONFLICT DO NOTHING` + fingerprint compare |
| 13 | Time | `effective_at` (event time, `asOf` filters on it) and `recorded_at`; future event times → 400 (5 min skew) |
| 14 | JSON | Strict: unknown fields → 400; amounts are integers; rates are decimal strings |
| 15 | Ledger engine | Own, on Postgres; DB enforces balance (deferred constraint trigger) and append-only history (triggers); on accounts only `balance` may change; TRUNCATE allowed for tests |
| 16 | Demo | `/ui` vanilla JS console + `/docs` Swagger (swagger-ui-dist from jsDelivr, pinned 5.33.0); no extra Gradle dependency |
| 17 | Runtime | Everything in Docker: app image + Postgres via compose; tests via the compose `test` profile; CI has a `docker` job (build, smoke test, tests in Docker) |
| 18 | Balances | Stored running `balance` per account, updated in the same tx via atomic `UPDATE … balance = balance + x`, accounts locked in id order as the last statements; the current report reads it; `asOf` sums postings; `Ledger.verify` reconciles (used in tests). Rejected: SUM on read, snapshots, queue-fed balances, optimistic locking, app-level locks (see README Trade-offs) |
| 19 | Schema evolution | No users yet: one migration (`V1__schema.sql`) edited in place; no compatibility shims |

## Ideas not done (candidates if time allows)

- Bitemporal `asOf` + `knownAt` reporting; closed periods.
- Payout / settlement / tax remittance entries (designs are in the README's out-of-scope table).
- Per-merchant pricing (a contract table) instead of global config.
