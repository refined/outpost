# Performance

On a budget of **2 CPUs and 3 GB** for the whole system (app 1 CPU, Postgres 1 CPU), short 20-second steps with p95 under 200 ms reached:
- **3500 requests/s mixed, 5200 captures/s** with group commit (`BALANCE_MODE=BATCHED`), since its captures are written as sets;
- **800–900 requests/s** with the default one-transaction-per-request mode (`STORED`).

Both keep p95 latency at 3–4 ms at 300 requests/s, and both keep balances exact. We started at ~300 requests/s. The table and chart below show what each step changed.

These are exploratory measurements, not validated sustained capacity. Their limits: unequal preload sizes, short steps that can stop early, and no repeats. Small differences between stages should not be attributed to code changes.

```mermaid
xychart-beta
    title "Mixed load, req/s: bars STORED, line BATCHED"
    x-axis ["1 Start", "2 Group commit", "3 Non-blocking DB", "4 No trigger", "5 await", "6 Batched inserts", "7 Refactor", "8 Set-based batch"]
    y-axis "requests/s" 0 --> 4000
    bar [300, 300, 600, 800, 800, 700, 900, 800]
    line [300, 300, 800, 1100, 1100, 1100, 1000, 3500]
```

Stage 1 had no batching, so the line starts at the STORED value. The same chart, interactive and per scenario, is on the console's **Performance** page (`/ui/#/performance`).

## Results

Stages 1–7 were measured from their own commits, with the same harness, on the same machine (mains power, no other containers), in one run on 2026-10-17/18. Stage 8 was measured on the evening of 2026-10-18 on the same machine, with ladders starting near the previous limits ([Method](#method)).
- **"Holds"** is the highest arrival rate held for 20 s with p95 < 200 ms and < 1% errors, at 100 rps resolution. Stages 1–7 ran with a 250 ms limit and are recomputed from their saved steps.
- **p95** and **CPU** are measured at a fixed 300 captures/s.
- **Ingest** is closed-loop bulk loading of 10k payments.

| Stage | Mode | Holds: capture / one merchant / mixed | p95 @300/s | CPU @300/s: app / Postgres (cores) | Ingest, payments/s |
|---|---|---|---|---|---|
| 1. Start | STORED | 200 / < 300 / 300 | 30 ms | 0.11 / 0.53 | 326 |
| 2. Group commit | BATCHED | 300 / 300 / 300 | 92 ms | 0.13 / 0.52 | 296 |
| 3. Database work off the request threads | STORED | 500 / 400 / 600 | 4 ms | 0.13 / 0.28 | 469 |
| | BATCHED | 800 / 900 / 800 | 6 ms | 0.15 / 0.32 | 554 |
| 4. No balance trigger | STORED | 800 / 600 / 800 | 4 ms | 0.12 / 0.21 | 708 |
| | BATCHED | 1100 / 1100 / 1100 | 4 ms | 0.13 / 0.25 | 927 |
| 5. `await` in the batch commit | BATCHED | 1100 / 1100 / 1100 | 4 ms | 0.13 / 0.22 | 943 |
| 6. Batched inserts | STORED | 700 / 700 / 700 | 4 ms | 0.12 / 0.21 | 731 |
| | BATCHED | 1100 / 1100 / 1100 | 5 ms | 0.12 / 0.21 | 970 |
| 7. Refactor | STORED | 900 / 600 / 900 | 3 ms | 0.13 / 0.21 | 706 |
| | BATCHED | 1000 / 1100 / 1000 | 4 ms | 0.12 / 0.20 | 938 |
| | BATCHED, batches of 1000, 50 ms wait | 600 / 700 / 700 | 106 ms | 0.10 / 0.18 | 268 |
| **8. Set-based batch writes** | **STORED** | **900 / 700 / 800** | **3 ms** | **0.14 / 0.19** | **525** |
| | **BATCHED** | **5200 / 5400 / 3500** | **4 ms** | **0.12 / 0.19** | **1479** |

Not shown, because it does not change: the balance report takes 2–4 ms at 100k payments in both modes, and balances are exact in both. The books balanced and every merchant reconciled after every run, including 3.3 million payments after stage 8's `BATCHED` run.

### Where the limit is

Stage 8, the highest holding step of each scenario, with CPU averaged over that step (`docker stats`, % of each container's 1-CPU quota). "`BATCHED`, per item" is stage 7's batching (commit `84697ac`), measured in the same run.

| Mode | Scenario | Holds, req/s | p95 | App CPU | Postgres CPU | What stops the next step |
|---|---|---|---|---|---|---|
| `STORED` | capture | 900 | 6 ms | 37% | 56% | row locks: 63% of active backends wait on one |
| | one merchant | 700 | 41 ms | 31% | 55% | row locks: 81% |
| | mixed | 800 | 40 ms | 37% | 56% | row locks: 68% |
| `BATCHED`, per item | capture | 1000 | 58 ms | 32% | 48% | the writer: statements one by one |
| | one merchant | 1000 | 52 ms | 32% | 51% | the writer |
| | mixed | 900 | 46 ms | 35% | 54% | the writer |
| `BATCHED`, set-based | capture | **5200** | 69 ms | 42% | 84% | Postgres CPU and the writer |
| | one merchant | **5400** | 77 ms | 45% | 76% | Postgres CPU and the writer |
| | mixed | **3500** | 79 ms | 46% | 84% | Postgres CPU and the writer; refunds still go one by one |

- **`STORED`** is stopped by the shared balance rows (`psp_clearing`, `fee_revenue`, `tax_payable`, and with one merchant `merchant_payable`): each request holds their locks until its commit. Postgres has CPU to spare since the capture stopped reading its rows back; before that change, a diagnostic run measured Postgres at 0.97 of its core at 800 req/s.
- **`BATCHED`, per item**, leaves both containers half idle. The single writer ran each capture's statements in turn: savepoint, payment, entry, postings, two read-backs, release. That is ~7 round trips and ~0.85 ms per capture, with the writer busy 92% of the time. The app and Postgres took turns, which capped it at ~1000 req/s.
- **`BATCHED`, set-based**, writes a batch's captures with one statement per table, whatever the batch size. The next limits are Postgres CPU (76–84%) and the one writer. k6 shares the host and is not CPU-limited, so above ~5000 req/s the generator may count too.

## How we got here

**1. Six ways to keep a balance.** We compared them first; the definitions and numbers are in the [deep dive](#the-six-starting-methods).
- Two were ruled out on reads:
  - summing postings on every read: the report took 9 s at 100k payments;
  - a snapshot plus the tail since its last refresh: reads pay for the tail.
- Two had nothing to gain:
  - a snapshot read without the tail: up to 1 s stale, and writes no cheaper;
  - hot accounts split into sub-accounts.
- TigerBeetle (an external ledger) needed a dual write and most of the memory budget.

We kept **stored running balances**. They were exact, the simplest, and as fast as anything else. All six sat at 200–400 req/s for a reason found only in step 3.

**2. Group commit (`BATCHED`).** One writer thread runs queued requests in batches: one transaction, one commit and one balance update per account per batch, with a savepoint per request. On its own it changed nothing, because the cap from step 3 was still in place.

**3. Database work off Ktor's request threads.** Profiling (JFR plus `pg_stat_statements`) showed neither the app nor Postgres was busy.
- With a 1-CPU limit the JVM sees one core, so Ktor starts only 3 request threads.
- Every handler blocked on JDBC there, so no more than 3 requests were ever in flight.

Moving the database work to the IO dispatcher doubled `STORED` and almost tripled `BATCHED`.

**4. No deferred balance trigger.** The database re-checked each journal entry's balance once per inserted row: 5 times per capture, at commit. The check stays in code (`validatedPostings` via `LedgerWriter`). SQL statements per capture fell from 18 to 13, and both modes gained another 30–40%.

**5–7. Smaller steps** (detail in the deep dive):
- `await` instead of a blocking wait in the batch commit;
- batched inserts in the driver: 3 statements fewer per capture;
- the table-object refactor.

None moved the ceiling. Batches of 1000 with a 50 ms wait were worse: every request waits for its batch, so latency rises to ~100 ms and the latency limit is reached at 600–700 req/s. With no wait (the default) a batch grows by itself under load.

**8. Set-based batch writes.** Timing the batch writer showed it busy 92% of the time at 0.85 ms per capture, while the queue and the commit alone cost ~0.18 ms. The limit was the capture's own statements, sent one at a time. Now a batch's captures are one multi-row `INSERT` per table:
- payments, with `ON CONFLICT DO NOTHING RETURNING`;
- journal entries;
- postings.

Responses are built from memory; only replays are read back. Refunds keep a savepoint each. `BATCHED` went from ~1000 to 3500–5400 req/s. `STORED` uses the same capture path and no longer reads its rows back.

## Deep dive

### The six starting methods

Stage 1, commit `0ffcf3f`. Every method was capped near 300 req/s by the 3 request threads (step 3), so their write numbers are close. The differences that matter are on reads and in operations.

| Method | On a write | On a balance read | Freshness | Holds: capture / one merchant / mixed | Report p95 @100k payments |
|---|---|---|---|---|---|
| Stored balances | postings, plus `UPDATE balance` on each account | read the column | exact | 200 / < 300 / 300 | 2 ms |
| Computed on read | postings only | `SUM` of all postings | exact | 300 / 300 / **100** | **9.1 s** |
| Snapshot + tail | postings only; snapshot refreshed every 1 s | snapshot + postings since the refresh | exact | 300 / 400 / 200 | 10 ms |
| Snapshot only | postings only; snapshot refreshed every 1 s | snapshot | up to 1 s stale (1004 ms max measured) | 400 / 200 / 300 | 3 ms |
| Sharded hot accounts | like stored, with clearing/fee/tax split into 8 sub-accounts | sum of the sub-accounts | exact | 300 / 400 / 400 | 2 ms |
| TigerBeetle | Postgres business row + TigerBeetle transfers (dual write) | `lookupAccounts` | exact | 200 / 300 / 200 | 28 ms |

- **Computed** fails on reads: with 5% balance reports in the mix it holds only 100 req/s.
- **Snapshot + tail** pays on every read: at 300 captures/s its p95 was 57 ms against 6–30 ms for the others.
- **Snapshot only** is cheap to read but stale, and it does not make writes cheaper.
- **Sharding** spreads row locks, but in stage 1 there was no lock contention to remove (3 requests in flight). By the final stage `STORED` does wait on locks (below); group commit removes those waits without changing the chart of accounts.
- **TigerBeetle** ran with app 0.8 CPU / 384 MB, Postgres 0.4 CPU / 256 MB and TigerBeetle 0.8 CPU / 2.4 GB, because it allocates ~2.3 GB at start-up.
  - p95 at 300 captures/s was 580 ms.
  - Every request writes Postgres (payment, idempotency) and then TigerBeetle.
  - Under this budget it cannot show what it is built for; see [ledger-comparison.md](ledger-comparison.md).

### Gain per step (mixed load, best mode)

| Step | Holds | Change | Ingest, BATCHED |
|---|---|---|---|
| 1 → 2 group commit | 300 → 300 | none (capped by the threads) | — |
| 2 → 3 database work off the request threads | 300 → 800 | **+167%** | 296 → 554 |
| 3 → 4 no trigger | 800 → 1100 | **+38%** | 554 → 927 |
| 4 → 5 `await` | 1100 → 1100 | — | 927 → 943 |
| 5 → 6 batched inserts | 1100 → 1100 | — | 943 → 970 |
| 6 → 7 refactor | 1100 → 1000 | — | 970 → 938 |
| 7 → 8 set-based batch writes | 1000 → 3500 | **+250%** | 938 → 1479 |

`STORED` over the same steps: 300 → 600 → 800 → 700 → 900 → 800.
- The STORED values differ across commits and preload sizes; this campaign does not establish repeatability of the same code or prove the cause of that variation.
- BATCHED results cluster within one 100 req/s step across stages 4–7; repeated runs are needed to measure its variability.

### Latency

- **At a fixed 300 captures/s**, p95 fell from 30 ms (stage 1, near its ceiling) to 3–4 ms from stage 3 on, in both modes.
- **At the ceiling** the two modes fail differently:
  - `STORED` stays fast until the lock queue builds: p95 6–41 ms at its highest holding steps;
  - `BATCHED` degrades gradually as batches grow: p95 69–79 ms at its highest holding steps, 210–250 ms one step above.
- **A batch wait costs latency directly.** With `BATCH_MAX_WAIT_MS=50`:
  - p95 at 300 captures/s was 106 ms;
  - closed-loop ingest fell to 268 payments/s, because each of the 24 loaders waits for its batch.

  Keep the wait at 0 unless batches need to be larger at low load.

### Where the load is

- **Neither container is CPU-bound below the ceiling.** At 300 captures/s the app used 0.12–0.13 cores and Postgres 0.20–0.21, out of 1.0 each. In stage 1 Postgres used 0.53, because 300/s was close to that stage's limit.
- **App CPU** (JFR, final stage):

  | Share | Where |
  |---|---|
  | ~40% | HTTP (Ktor / Netty) |
  | ~25% | our code |
  | ~15% | the JDBC driver and Hikari |
  | ~10% | coroutines |
  | ~8% | JSON |

- **What requests wait on in Postgres** (final stage):

  | | `STORED` | `BATCHED` |
  |---|---|---|
  | JDBC `commit` call | 37% | 47% |
  | balance `UPDATE`s | 31% | 8% |
  | inserts | the rest | entry, postings and payment inserts, the rest |

- **What limits each mode at its ceiling:** see [Where the limit is](#where-the-limit-is).

### What we dropped

| Variant | Why dropped |
|---|---|
| Computed on read | reads grow with history |
| Snapshot + tail | reads pay the tail |
| Snapshot only | stale reads, writes no cheaper |
| Hot-account sharding | group commit removes the lock waits without changing the chart of accounts |
| TigerBeetle | dual write, and most of the memory budget |
| Batches of 10 | an earlier run: slower than `STORED`, because the single writer serialises work that the pool ran in parallel |
| A 50 ms batch wait | latency |

The code of the removed variants is at commit `0ffcf3f`.

## Setup

- **Budget:** 2 CPUs and 3 GB for the system under test, set with Docker limits ([`perf/compose.limits.yml`](../perf/compose.limits.yml)):
  - app 1.0 CPU / 1280 MB;
  - Postgres 1.0 CPU / 1792 MB (`shared_buffers` 448 MB).

  A limit is a CPU-time quota (cgroups), not pinned cores. The load generator runs outside the limit.
- **Host:** Docker Desktop 29.1.3 on Windows 11, a 16-CPU / 16 GB VM, a laptop on mains power ("Performance" power mode), no other containers. On battery the same code measured about half as fast, so all numbers here come from one mains-powered run.
- **Commits:** stage 1 `0ffcf3f`, 2 `80c90d9`, 3 `e5580cc`, 4 `8c524e2`, 5 `69cd154`, 6 `220572b`, 7 `96e5872`, 8 `813a712`. Stage 8's "`BATCHED`, per item" is `84697ac`. Each older commit ran in a git worktree with the current harness.

## Method

- **Load:** [k6](https://grafana.com/docs/k6/) 2.3.0 ([`perf/k6/bench.js`](../perf/k6/bench.js)) at a constant arrival rate (open loop), driven by [`perf/run-benchmarks.sh`](../perf/run-benchmarks.sh). Each variant starts from an empty database.
  - **Why k6 and not Kotlin?** k6 has a Go engine; only the scenarios are JavaScript. Its arrival-rate executor starts requests on schedule even when the service slows down, so latency is not hidden by coordinated omission. Gatling has a Kotlin DSL; a hand-written loop on coroutines would measure the wrong thing.
- **Warm-up and preload:** 15 s at 200 req/s, then 10k payments (the ingest number) and the balance report at 20 req/s. Stages 1 and 7 also load 100k payments and measure the report again; stage 8 does not (`QUICK=1`).
- **Scenarios:**
  - `capture`: payments over 100 merchants, 3 currencies;
  - `hot`: every payment to one merchant in one jurisdiction;
  - `mixed`: 85% captures, 10% partial refunds, 5% balance reports.
- **Ladder:**
  - each scenario starts at 400 req/s, or near its previous limit (`START_CAPTURE` / `START_HOT` / `START_MIXED`, as in stage 8);
  - it climbs by 200, up to 6000, while steps hold, then tries 100 below the first failing step;
  - if the first step fails, it steps down by 100;
  - a step lasts 20 s, and stops early once it clearly cannot hold (k6 thresholds with `abortOnFail`), followed by a 5 s cool-down;
  - a step **holds** if it served ≥ 95% of its planned requests with p95 < 200 ms and < 1% errors (250 ms until 2026-10-18).
- **CPU per step:** `docker stats` for the app and Postgres once a second during every step. On the same steps it matched cgroup `cpu.stat` within 0.04 cores.
- **Profile at 300 captures/s:**
  - a 40 s step without a profiler gives CPU per container (`docker stats`) and SQL per capture (`pg_stat_statements`);
  - the app then restarts with JFR for a second step, used only for proportions ([`perf/analyse-jfr.sh`](../perf/analyse-jfr.sh)). JFR left on during the ladders cost a whole step.
- **Waits:** during every ladder step, `pg_stat_activity` is sampled every 200 ms. The result is the share of active backends waiting on a row lock or on IO.
- **Consistency:** after every variant, `/v1/ledger/verify` (stored balances against postings), the report's integrity check and per-merchant reconciliation must pass.

## Reproduce

```bash
perf/run-benchmarks.sh                                 # pg-stored, pg-batched-100, pg-batched-1000 (~45 min); perf/results/<time>/
perf/run-benchmarks.sh "pg-x:BALANCE_MODE=STORED"      # any settings as name:SETTING=value
COMPOSE_PROJECT_NAME=outpost-bench APP_HOST_PORT=18090 POSTGRES_HOST_PORT=15442 QUICK=1 \
  perf/run-benchmarks.sh "batched-100:BALANCE_MODE=BATCHED START_CAPTURE=3000 START_HOT=1400 START_MIXED=1500"
```

Older stages run the same way from a worktree at their commit. Each run writes `summary.md` and `results.json`. The script removes its project's `pgdata` volume before every variant, so give it its own `COMPOSE_PROJECT_NAME` when the dev stack holds data you want to keep.
