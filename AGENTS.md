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
- **Status:** feature-complete for the brief, including the nice-to-haves (partial refunds, multi-currency). Extras:
  - `asOf` reports, journal, analytics API and a multi-page console with per-merchant breakdowns;
  - a deterministic ~100k-payment demo dataset;
  - two balance modes, `STORED` and `BATCHED` (group commit), chosen after benchmarking (docs/performance.md); computed, snapshot, sharded and TigerBeetle variants were measured and removed (commit `0ffcf3f` has them).

  64 tests: 26 unit, 38 integration. CI runs the Docker path.
- **Platforms:** must run on macOS, Linux and Windows. This is guaranteed by running everything in Linux containers (Docker / Docker Desktop).
- **What is judged:** reasoning and trade-offs as much as the code. Every ambiguity gets an explicit, documented decision.

## Stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin 2.4, JVM target 17 | Builds with any JDK 17+ |
| HTTP | Ktor 3 (Netty), kotlinx.serialization | |
| DB access | Plain JDBC + HikariCP | No ORM: SQL and transaction boundaries stay explicit |
| Migrations | Flyway (`src/main/resources/db/migration`) | |
| Database | PostgreSQL 17 (docker-compose) | |
| Tests | JUnit 6, Ktor `testApplication`, Testcontainers; Gradle JVM Test Suites `test` + `integrationTest` | In Docker: compose `test` service with `TEST_DB_URL`; on a host: Testcontainers |
| CI | GitHub Actions, `.github/workflows/ci.yml` | One docker compose job: build image, smoke test through the API, tests in Docker |
| Runtime | Docker: `Dockerfile` (multi-stage) + `docker-compose.yml` + optional `.env` (`.env.example`) | Host needs only Docker |
| Build | Gradle 9.8.1 from the official `gradle:9.8.1-jdk21` Docker image; Kotlin DSL; versions inline in `build.gradle.kts` | **No wrapper** (user decision: everything via Docker). Host builds need a local Gradle 9 |
| Console | Vanilla JS, hash router; Chart.js 4.5.1 + chartjs-chart-sankey 0.19.1 from jsDelivr (pinned) | Colours from the dataviz reference palette, light + dark |
| Load / data | k6 (`grafana/k6:2.3.0`, compose profile `tools`), scripts in `perf/k6` | `seed.js` (demo data), `bench.js` (benchmark steps) |

There is no DI framework: the app is wired by hand in `Application.module(dataSource, pricing, clock)`. Keep dependencies minimal, and justify any new one.

## Layout

A standard single-module Gradle layout at the root, so IntelliJ opens it as a Gradle project with no setup. (A `service/` subfolder was tried on 2026-10-14 and reverted: IDEA lost the Gradle import.) Keep the root minimal and add no new top-level files without a reason.

```
docker-compose.yml               the whole stack (postgres, app, test profile); settings from optional .env
.env.example                     every compose setting with its default (.env is gitignored)
README.md, AGENTS.md, CLAUDE.md  docs for reviewers / agents (CLAUDE.md just imports this file)
docs/requirements.md             what we build and why
docs/design.md                   all decisions and trade-offs, tests, CI, configuration, code layout (README details)
docs/demo.http                   demo scenario as HTTP requests
docs/domain/                     MoR background: VAT, money flows, ledger posting examples
.github/workflows/ci.yml         CI
build.gradle.kts, settings.gradle.kts  Gradle build (no wrapper; versions inline)
Dockerfile, .dockerignore        image build (gradle:9.8.1-jdk21 -> JRE 21)
perf/k6/                         lib.js (deterministic data), seed.js (demo dataset), bench.js (one benchmark step)
perf/compose.limits.yml          resource limits override (2 CPUs / 3 GB for app + db)
perf/run-benchmarks.sh           runs all engine variants, writes perf/results/<time>/summary.md (results are gitignored)
docs/ledger-comparison.md        platforms compared + the decision; docs/performance.md: results per stage, deep dive, method
src/main/kotlin/io/github/refined/morledger/
  Application.kt                 main(), AppConfig (env), Application.module()
  money/Allocation.kt            pure money math: capture split, refund apportionment, RefundFeePolicy
  ledger/Model.kt                ledger models + pure validatedPostings
  ledger/LedgerTable.kt          SQL + mapping: accounts, journal, postings, balances, verify
  ledger/LedgerWriter.kt         posting validation, account-id cache, ordered writes, immediate or collected balance deltas
  ledger/LedgerBatchCommit.kt       BALANCE_MODE=BATCHED: one writer, batches in one tx, set-based groups (captures), savepoint per other request, one balance UPDATE per account
  ledger/ChartOfAccounts.kt      account codes + postings for capture/refund
  payments/                      PaymentService (tx, idempotency, locking), PaymentsTable + RefundsTable (SQL), Model (+ DomainException types)
  reports/BalanceReport.kt       ledger balances -> report
  reports/Analytics.kt           report assembly, time-series gap filling, reconciliation
  reports/AnalyticsTable.kt      SQL aggregates from payments + refunds
  api/                           Routes (base routes + /v1 wiring), PaymentRoutes, ReconciliationRoutes, AnalyticsRoutes, Dto (wire format), Errors (DomainException -> HTTP status)
  db/Database.kt                 Hikari + Flyway, DataSource.transaction { }
  db/Sql.kt                      safe sql { "... ${param(value)}" }, JDBC query/update/batch, UTC binding
src/main/resources/
  db/migration/                  V1__schema.sql: the only migration (edit in place while there are no users; wipe the pgdata volume after changes)
  web/                           ui/ (demo console, vanilla JS; performance.json feeds #/performance), docs/ (Swagger page), openapi.yaml
src/test/kotlin/...              unit tests: pure functions only (no DB, no app)
src/integrationTest/kotlin/...   tests through the HTTP API (api/ApiSupport.kt helpers); TestDatabase = one shared Postgres per JVM
```

## Commands

**Everything runs in Docker** (user requirement: the host needs only Docker). Multi-stage `Dockerfile`: `build` stage = `gradle:9.8.1-jdk21`; runtime = JRE 21, non-root user, healthcheck on `/health`.

```bash
docker compose up -d --build --wait              # Postgres (127.0.0.1:5442) + app (127.0.0.1:8090)
docker compose --profile test run --rm test      # test suite in the build stage, against postgres-test (tmpfs)
docker compose logs -f app
docker compose --profile tools run --rm k6 run seed.js                     # demo data (~100k payments, ~3 min)
perf/run-benchmarks.sh [variant...]                                         # pg-stored pg-batched-100 pg-batched-1000, or "name:SETTING=value"
docker compose --profile test down               # stop everything; -v also drops pgdata
```

Host workflow (optional, for fast edit-run loops; needs JDK 17+ and Gradle 9 installed):

```bash
docker compose up -d --wait postgres
gradle test                   # unit tests only, no Docker needed
gradle build                  # compile + both suites (integration via Testcontainers)
gradle run                    # service on http://localhost:8090 (stop the app container first: same port)
```

Environment variables: `PORT` (8090), `DB_URL` (`jdbc:postgresql://localhost:5442/mor_ledger`), `DB_USER`, `DB_PASSWORD`, `MOR_FEE_RATE` (0.05), `REFUND_FEE_POLICY` (`MOR_KEEPS_FEE` | `MOR_RETURNS_FEE`), `BALANCE_MODE` (`STORED` | `BATCHED`), `BATCH_MAX_SIZE` (100), `BATCH_MAX_WAIT_MS` (0).

URLs: `/ui/` demo console, `/docs/` Swagger, `/openapi.yaml` spec. **Keep `openapi.yaml` in sync with `api/Dto.kt` and the `api/*Routes.kt` files.**

Tests use `TestDatabase`:
- if `TEST_DB_URL` (+ `TEST_DB_USER`, `TEST_DB_PASSWORD`) is set, they use that database;
- otherwise they start Postgres via Testcontainers.

With `TEST_DB_URL`, tests **drop and recreate the schema** first and truncate tables between tests (`TestDatabase.reset()`), so point it at a dedicated database, never the dev one.

## CI

`.github/workflows/ci.yml` has one job, the same path as the README. It runs on push to `main`, on PRs and on manual dispatch:
- `docker compose up -d --build --wait`;
- an API smoke test (capture → refund → balanced report → `/ui/`);
- `docker compose --profile test run --rm test`.

On failure it prints the container logs. Until 2026-10-14 there was also a Gradle matrix (Ubuntu JDK 17/25, macOS, Windows with native Postgres). It was dropped with the wrapper, because the host path is no longer the delivered one.

Rules:
- Code must stay cross-platform: no shell-specific build steps; use `java.nio.file` paths, not string concatenation.
- Tests go through the HTTP API (`src/integrationTest`, helpers in `api/ApiSupport.kt`). Unit tests (`src/test`) are only for pure functions that need no setup. Go below the API only when the scenario cannot be set up through it (raw-SQL tampering, a transaction held open), and say why in the test.
- A test that needs the database uses `TestDatabase.dataSource`; never start a separate container per test class.

## Environment notes

- **Live demo: https://mor.gamx.dev** (2026-10-19), on the Hetzner box shared with share.travel and nevis.gamx.dev.
  - `/opt/mor` holds a `git archive HEAD` plus two server-only files, never in the repo:
    - `compose.gamx.yml`: no published ports; 1 CPU / 768 MB each; the app on `docker_default` as `mor-app`; Postgres aliased `mor-postgres`, because on `docker_default` the name `postgres` is share.travel's database;
    - `.env`: `COMPOSE_PROJECT_NAME=mor`, `COMPOSE_FILE`, a random `POSTGRES_PASSWORD`, `BATCHED`.
  - Caddy: `/opt/caddy-sites/mor.caddy` in the shared `sharetravel-caddy`, Cloudflare-only guard, `noindex`.
  - Update: archive, untar over `/opt/mor`, `docker compose up -d --build --wait`.
  - Remove: `docker compose down -v`, delete `mor.caddy`, `caddy reload`, `rm -rf /opt/mor`, and drop the footer link in `/opt/sites/gamx/index.html`.
- The development machine runs Windows. Git Bash and PowerShell are both available. `.gitattributes` forces LF everywhere, so Docker build inputs are the same on every OS.
- Ports 5432 and 8080 are often taken by other local projects, which is why the defaults are 5442 and 8090. Windows reserves some high port ranges (e.g. 55389–55488), so don't pick ports there.
- `system-design/` is a local-only, gitignored folder. Never add it to git or link to it. Domain material that should be public lives in `docs/domain/`.
- The repo is public. Pushing straight to `main` is fine for now; never force-push. Keep CI green: run the tests (`docker compose --profile test run --rm test`) before pushing.
- Prefer the Docker workflow. If you use `gradle run` from a background shell, killing the shell leaves the JVM running (Windows); free port 8090 (stop the `java.exe` owning it) before restarting, or you will be testing the old build.
- After changing code, `docker compose up -d --build --wait` rebuilds the image; without `--build` the old image keeps running.
- **Env-dependent settings + `depends_on` gotcha:** `docker compose run k6` recreates `app` if the compose config differs. A variable like `BALANCE_MODE` set only for the first command silently flips the app back to the default. Export such variables for the whole session (the benchmark script does), or use `run --no-deps`.
- In Git Bash, container paths in `docker run` arguments get rewritten (`/scripts` → `C:/Program Files/Git/scripts`); the k6 service runs from `/scripts` so scripts are passed by name; use `MSYS_NO_PATHCONV=1` otherwise.
- Benchmarks: run them on an otherwise idle Docker host. The stack is CPU-limited, but k6 is not, and it shares the VM.
- The browser caches the console aggressively; it is served `no-cache`, but a page already open needs a reload after a rebuild.

## Coding conventions

- All files, code, comments and commit messages are in English.
- **SQL:** use multiline queries with `FROM`, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT` (with `OFFSET`), `SET`, `VALUES` and `RETURNING` on separate lines, including subqueries. Put optional conditions inline at `WHERE ${where { ... }}`; `where` joins them with `AND` and returns `TRUE` when empty. Bind values through `param` in SQL order.
- **Comments:** none by default. Only a lock order, a race, an external quirk or an invariant enforced elsewhere earns one line. No KDoc on obvious types, no comments repeating a config key, an option or a design-doc decision.
- **Money:**
  - amounts are `Long` minor units plus an ISO-4217 currency code, never `Double`/`Float`;
  - rates and percentages are `BigDecimal` (or basis points as `Long`);
  - rounding happens in one place, with a documented rule.
  - refund apportionment lives directly in `Allocation.allocateRefund`; tie-break tests exercise this function.
- **Ledger:**
  - append-only: never `UPDATE`/`DELETE` journal entries or postings; corrections are new entries;
  - every journal entry balances (Σ debits = Σ credits per currency), enforced in code and, where practical, in the DB;
  - balances are derived from postings.
- **Transactions:** one business operation is one DB transaction (`DataSource.transaction { }`). The business record (payment, refund) and its journal entry are written together.
- **Analytics SQL:** keep payment and refund aggregates as separate `paymentFlows` and `refundFlows` queries; their source tables, columns and event-time filters stay explicit.
- **Idempotency:** every mutating API must be safe to retry (an idempotency key or natural key such as the external payment id).
- **Migrations:** never edit an applied migration; add a new `V{n}__*.sql`.
- **Tests:** money flows are covered through the API against real Postgres; pure functions get unit tests.
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
| 15 | Ledger engine | Own, on Postgres; DB enforces append-only history (triggers) and that accounts only change their `balance`; TRUNCATE allowed for tests. Entry balance is enforced in code (`validatedPostings` via `LedgerWriter`, single write path); `verify` reconciles account balances against postings, not individual entry balance. The deferred balance trigger was dropped on 2026-10-17 (it ran 5× per capture at commit) |
| 16 | Demo | `/ui` vanilla JS console + `/docs` Swagger (swagger-ui-dist from jsDelivr, pinned 5.33.0); no extra Gradle dependency |
| 17 | Runtime | Everything in Docker: app image + Postgres via compose; tests via the compose `test` profile; CI has a `docker` job (build, smoke test, tests in Docker) |
| 18 | Balances | Stored running `balance` per account, updated in the same tx via atomic `UPDATE … balance = balance + x`, accounts locked in id order as the last statements; the current report reads it; `asOf` sums postings; `LedgerTable.verify` reconciles (used in tests). Rejected: SUM on read, snapshots, queue-fed balances, optimistic locking, app-level locks (see README Trade-offs) |
| 19 | Schema evolution | No users yet: one migration (`V1__schema.sql`) edited in place; no compatibility shims |
| 20 | Build tooling | No Gradle wrapper; build in the `gradle:9.8.1-jdk21` image; versions inline in `build.gradle.kts`; CI = docker job only |
| 21 | README | Short; the user's structure: what/why → why I built the ledger (learning motive, architectural choice, compact comparison) → how it works (UI, Swagger, API with only the 3 endpoints from the brief, diagram) → how the ledger works → install/config → code reading guide → short assumptions and trade-offs. The walkthrough selects `BATCHED` with a `.env` override and links to the benchmark history; the runtime default remains `STORED`. Distinguish integrated/benchmarked options from documentation reviews; performance claims refer to measured commits and are not a product ranking. Keep the live demo link near the top. Details go to `docs/design.md` |
| 22 | Balance modes | `BALANCE_MODE=STORED` (default): one tx per request. `BATCHED`: group commit (`LedgerBatchCommit`): a single writer runs queued requests in batches, one tx: captures as a `BatchGroup` (one multi-row INSERT per table, response from memory, only replays read back), refunds each under a savepoint, one summed balance UPDATE per account, answers after commit, per-item retry if the commit fails. Balances exact in both; a test proves identical reports. Keep STORED/BATCHED selection explicit in PaymentService: captures and refunds are both `BatchGroup`s (captures `TOGETHER`, refunds `INDIVIDUAL`), with no lambda-based submit; STORED calls the same write function in its own transaction with `deltas = null`. The writer thread starts with an explicit `start()` in `Application.module`; `submit` fails fast if it is not running. The batch queue uses one Work type with BatchExecution: TOGETHER groups inputs by handler for set-based writes; INDIVIDUAL calls the same list-based contract with one input under a savepoint, rolling back on thrown or returned failure. BatchGroup retains typed inputs per transaction connection and clears them in finally; Work retains Result<T> until commit. This avoids Any and unchecked casts without a Work hierarchy or commit interfaces; shared handlers isolate concurrent writers |
| 23 | Removed variants (2026-10-16) | COMPUTED (slow reads), snapshot + tail (reads pay the tail), snapshot-only (stale, writes no cheaper), hot-account sharding (test uninformative: merchant row not sharded, CPU-bound) and the TigerBeetle engine (starved by 2.3 GB static memory + dual write). Findings in docs/performance.md and docs/ledger-comparison.md; code at commit `0ffcf3f` |
| 24 | Analytics | Computed from the business tables; `reconciled` = records-based merchant balance == ledger balance |
| 25 | Console | Pages: Overview / Merchants / Merchant / Ledger / Simulate; server-side aggregates and paging for the 100k dataset; no dual-axis charts (balance has its own chart) |
| 26 | Demo data | `perf/k6/seed.js`: deterministic (SEED), idempotent (PREFIX); ~100 Zipf-sized merchants, 3 currencies, 9 jurisdictions, ~7% refunds, 4 refund-heavy anomalies |
| 27 | Benchmark | k6 (Go engine, JS scenarios; user decision to keep it over Gatling/Kotlin). 2 CPUs / 3 GB per variant: app 1.0/1280M + db 1.0/1792M. Ladders start at `START_CAPTURE` / `START_HOT` / `START_MIXED` (default 400, so a re-run can start at the known limit), climb by 200 up to `MAX_RATE` (6000) while a step holds (served ≥ 95%, p95 < 200 ms, < 1% errors; 250 ms until 2026-10-18, older stages recomputed), then try 100 below the first failure; failing steps abort early. Every step samples CPU per container with docker stats; the summary reports rps, p95 and app / Postgres CPU at each scenario's highest holding step. Profile at 300/s: docker stats + pg_stat_statements without a profiler, JFR in a separate step. Variants are presets or `name:SETTING=value`; `results.json` per run. Stages 1–7 come from one campaign (2026-10-17/18), stage 8 from a second run (2026-10-18, Compose project `outpost-bench`, `QUICK=1`): every stage from its own commit via a worktree, mains power, quiet host. `docker stats` CPU matched cgroup `cpu.stat` within 0.04 cores |
| 28 | Blocking JDBC in Ktor | Route handlers run database work in `blocking { }` = `withContext(Dispatchers.IO)` (`api/Routes.kt`). With a 1-CPU container Ktor has only 3 request threads; blocking JDBC on them capped concurrency at 3 (found with JFR, 2026-10-17). Next steps if needed: virtual threads, then an async driver |
| 29 | Batch commit wait | `LedgerBatchCommit.submit` is `suspend` and awaits its batch (`CompletableFuture.await`); a waiting request holds no thread. `PaymentService.capture/refund` are `suspend`; `STORED` runs on `Dispatchers.IO` inside the service |
| 30 | Set-based batch writes (2026-10-18) | `BATCHED` writes a batch's captures with one multi-row INSERT per table (payments `ON CONFLICT DO NOTHING RETURNING`, entries, postings; chunked so a statement stays under 32 767 bind parameters, `chunkedForParameters`), builds responses from memory and reads back only replays. Before, the single writer ran ~7 round trips per capture (~0.85 ms, writer 92% busy), which capped `BATCHED` at ~1000/s. Measured 2026-10-18 (p95 < 200 ms): 5200 captures / 5400 one merchant / 3500 mixed per second, against 1000 / 1000 / 900 for the per-item batching and 900 / 700 / 800 for `STORED` (row locks, Postgres ~55% CPU). `reWriteBatchedInserts` was dropped: no batched INSERT is left |
| 31 | SQL layer (2026-10-17) | Stateless `internal object *Table` components own SQL and row mapping; a Table may span related physical tables. `LedgerWriter` owns validation, account caching and posting coordination; `LedgerBatchCommit` owns queue/groups/savepoints/commit/retry. `AnalyticsTable` owns queries; `Analytics` assembles reports. Safe `sql { "... ${param(value)}" }` keeps values as JDBC parameters; dynamic filters register values in the same builder. No ORM or new dependency. |
| 32 | Performance page | Console page `#/performance` reads the static `web/ui/performance.json` (curated from the campaign's `results.json`): KPI tiles, the per-stage chart (STORED vs BATCHED, scenario switch) with a table view, the six starting methods. `docs/performance.md` is the long form: results first, the story, deep dive, setup, method |

## Ideas not done (candidates if time allows)

- Benchmark audit (2026-10-18): the runs are exploratory, not sustained-capacity validation. Targeted confirmation still needed: equal datasets, repeated longer steps, separate batch cap/wait comparisons, and strict harness error handling.

- Bitemporal `asOf` + `knownAt` reporting; closed periods.
- Payout / settlement / tax remittance entries (designs are in the README's out-of-scope table).
- Per-merchant pricing (a contract table) instead of global config.
