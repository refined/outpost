# Performance: ledger variants under one budget

How fast can the service take captures and refunds, and does the way balances are kept change that? Everything below ran on the same code, data and resource budget, in four rounds, newest first:

1. **Round 4:** three changes suggested by profiling, each measured before / after / before:
   - dropping the deferred balance trigger;
   - `await` in the batch commit;
   - batched inserts in the driver.
2. **Round 3:** the two modes in the code after profiling found the real limit: database work blocking Ktor's 3 request threads. Fixed with an IO dispatcher.
3. **Round 2:** `STORED` (default) and `BATCHED` (group commit), with lock and IO waits sampled from Postgres; still limited by those 3 threads.
4. **Round 1:** six earlier variants. Four were removed afterwards; their code is at commit `0ffcf3f`.

## Setup

- **Budget:** 2 CPUs and 3 GB for the whole system under test, set with Docker limits ([`perf/compose.limits.yml`](../perf/compose.limits.yml)): app 1.0 CPU / 1280 MB, Postgres 1.0 CPU / 1792 MB (`shared_buffers` 448 MB). A limit is a CPU-time quota (cgroups), not pinned cores. The load generator runs outside the limit.
- **Host:** Docker Desktop 29.1.3 on Windows 11, a 16-CPU / 16 GB VM.
  - **Round 4** ran with no other containers. The trigger experiment ran on battery ("Silent" power mode); the others ran on mains power ("Performance"), where everything is about twice as fast.
  - **Round 3** ran next to the (mostly idle) sharetravel containers.
  - **Round 2** ran with no other containers.
  - **Round 1** ran next to other projects' containers.
  - Treat differences under ~50 rps as noise. `STORED`'s capture limit came out at 200–400, 400 and 300 rps in three runs.
- **Load:** [k6](https://grafana.com/docs/k6/) 2.3.0 ([`perf/k6/bench.js`](../perf/k6/bench.js)), driven by [`perf/run-benchmarks.sh`](../perf/run-benchmarks.sh). Each variant starts from empty volumes.

## Method

1. **Warm-up:** 15 s at 200 rps, not reported.
2. **Preload and report latency:** seed 10k payments, then measure `GET /v1/reports/balances` at 20 rps; seed up to 100k payments and measure again. The seed is closed-loop (24 virtual users), so its throughput doubles as a bulk-ingest number.
3. **Throughput ladders:** a constant arrival rate (open loop), 20 s per step. A step **holds** if it keeps up with the target (≥ 95% of the planned requests), with p95 < 250 ms and < 1% errors. There are three scenarios (listed below).
   - Rounds 1–3 climbed 200, 250, 300, 350, 400, 500… until a step failed.
   - From round 4 a ladder starts at 400 and climbs while steps hold; if 400 fails, it steps down by 100 until one holds. A step that clearly cannot hold stops early (k6 thresholds with `abortOnFail`: too many dropped requests, or p95 or errors over the limit after 5 s).

   The scenarios:
   - `capture`: payments spread over 100 merchants and 3 currencies;
   - `hot`: every payment to one merchant in one jurisdiction, the worst case for shared rows;
   - `mixed`: 85% captures, 10% partial refunds, 5% balance reports.
4. **Waits (round 2):** during every step, `pg_stat_activity` is sampled every 200 ms. The result is the share of active Postgres backends waiting on a row lock, and on IO (mostly the WAL flush at commit).
5. **Profile (round 4):** at a fixed 300 captures/s, a 40 s step without a profiler gives CPU per container (`docker stats`) and SQL per capture (`pg_stat_statements`). The app then restarts with JFR for a second 40 s step, used only for proportions: where the app's CPU goes and which calls wait on Postgres ([`perf/analyse-jfr.sh`](../perf/analyse-jfr.sh)). JFR is not left on during the ladders: it cost `STORED` a whole step.
6. **Consistency:** after each variant, `/v1/ledger/verify` and the report's integrity check must pass, and every merchant must reconcile. All variants in every round did.
7. **Comparing a change (round 4): A-B-A.** The laptop's speed drifts (battery, power mode, temperature), so a change is measured as before / after / before again, back to back, with the "before" code in a separate git worktree. A change counts only if "after" beats both "before" runs.

**Why k6 and not Kotlin?**
- k6 is a load generator with a Go engine; only the scenarios are JavaScript.
- It runs from one Docker image with no build step.
- Its arrival-rate executor starts requests on schedule even when the service slows down, so latency is not hidden by coordinated omission.
- The Kotlin-native equivalent is Gatling (Java/Kotlin DSL, Gradle plugin). A home-made loop on coroutines would measure the wrong thing.

## Round 4: profiling-guided changes, measured A-B-A

Each change is measured before / after / before again, back to back, with the "before" code in a separate git worktree (see Method).
- The first experiment (the trigger) ran on battery, in the laptop's "Silent" power mode.
- The other two ran on mains power, in "Performance" mode.
- Numbers from different power states must not be compared: on mains power every variant holds about twice as much.

**1. Drop the deferred balance trigger** (on battery; runs `20261017-152137`, `-153656`, `-155401`).
- The trigger checked the same journal entry once per inserted row: 5 times per capture.
- Balance per entry is still enforced in code (`Ledger.validated`, the only write path), and `/v1/ledger/verify` detects anything written around it.

| Run | stored: capture / hot / mixed | batched-100: capture / hot / mixed | seed s / b, payments/s | SQL statements per capture s / b |
|---|---|---|---|---|
| before | 400 / 297 / 299 | 400 / 399 / 496 | 284 / 372 | 18 / 19 |
| **after** | **500 / 400 / 500** | **600 / 599 / 597** | **407 / 521** | **13 / 14.8** |
| before | 400 / — / 300 | — / 296 / 495 | 295 / 298 | 18 / 16.4 |

"After" beats both "before" runs everywhere. The battery was draining, so the second "before" is the weakest. Postgres CPU at 300 captures/s fell from 0.52 to 0.41 cores for `STORED`.

**2. `await` instead of `get()` in the batch commit** (`BATCHED` only, on mains power; runs `20261017-181905`, `-182744`, `-183640`).

| Run | capture / hot / mixed | seed, payments/s | app CPU ms per request @300 | Postgres CPU @300 |
|---|---|---|---|---|
| before | 1000 / 1000 / 800 | 814 | 0.63 | 0.28 |
| **after** | **1000 / 1000 / 1000** | **940** | **0.50** | **0.25** |
| before | 1000 / 1000 / 1000 | 857 | 0.57 | 0.27 |

The ceiling did not move within the ladder's resolution: every run holds 1000 and fails at 1300. A request waiting for its batch no longer holds a thread, so the app spends 12–20% less CPU per request and bulk ingest is 10–15% faster. A modest but consistent gain.

**3. `reWriteBatchedInserts=true`** (both modes, on mains power; runs `20261017-184658`, `-190332`, `-192003`). The driver sends a capture's 4 postings as one multi-row `INSERT`.

| Run | stored: capture / hot / mixed | batched-100: capture / hot / mixed | seed s / b | SQL statements per capture s / b |
|---|---|---|---|---|
| before | 800 / 600 / 800 | 1000 / 1000 / 1000 | 540 / 864 | 13 / 15 |
| **after** | **800 / 600 / 800** | **1000 / 1000 / 1000** | **678 / 873** | **10 / 12** |
| before | 800 / 600 / 800 | 1000 / 1000 / 1000 | 660 / 961 | 13 / 15 |

Three statements fewer per capture, and nothing else measurable: the ceilings are identical, and the seed difference is within the spread of the two "before" runs. Kept, because it costs nothing and removes round trips.

**Where things stand** (mains power):
- **`STORED` holds 800 / 600 / 800 rps.** Its limit is the hot accounts' row locks: in the one-merchant scenario 64–69% of active Postgres backends wait on a lock.
- **`BATCHED` holds ~1000 rps in every scenario, with 0% lock waits.** It fails at the next step (1300).
- **Profile of `STORED` at 300 captures/s,** where Postgres waits break down as:

  | Share | Where |
  |---|---|
  | 38% | commit (WAL flush) |
  | 27% | balance `UPDATE`s |
  | 15% | `Ledger.post` |
  | 10% | `insertPayment` |

  The app's CPU splits into Ktor/Netty 43%, our code 23%, coroutines 13%, JDBC 12%, JSON 7%.

**Not done: grouping a batch's SQL by hand.** `BATCHED` still runs ~12 statements per request, one after another, on the writer's connection. Grouping them means:
- one multi-row `INSERT` per table per batch;
- idempotency via `INSERT … ON CONFLICT DO NOTHING RETURNING` for the whole batch;
- a separate batched path for refunds, which lock and read their payment first.

That is a rewrite of `PaymentService`'s write path for one mode, so it is skipped. At ~1000 rps on 2 CPUs the service is far above its expected load.

## Round 3: database work off the request threads

Run `20261017-133224`; the sharetravel containers were running next to it, mostly idle.

**What profiling found.** A JFR recording of `STORED` at 280 rps, together with `pg_stat_statements`, showed that neither CPU was busy: the JVM used ~0.16 CPU and Postgres's SQL ~0.1 CPU.
- With a 1-CPU limit the JVM sees one core (`effectiveCpuCount = 1`), so Ktor starts only **3 request threads**.
- Every handler called blocking JDBC on those threads, so at most 3 requests were in flight, whatever the Hikari pool size.
- Of the time those threads spent waiting on the database:

  | Share | Where | Why |
  |---|---|---|
  | 56% | `commit` | WAL flush, plus the deferred balance check |
  | 15% | `Ledger.post` | the entry and its postings |
  | 12% | `insertPayment` | the payment row |
  | 11% | re-reading the payment | to build the response |
  | 7% | balance `UPDATE`s | |

**The fix.** Route handlers now run database work under `withContext(Dispatchers.IO)` (`blocking { }` in [`api/Routes.kt`](../src/main/kotlin/io/github/refined/morledger/api/Routes.kt)), so a request waiting for Postgres no longer holds a request thread. Concurrency is now bounded by the connection pool (10), not by 3 threads.

**Highest holding rate:**

| Variant | capture (100 merchants) | hot (1 merchant) | mixed (85/10/5) | seed: payments/s |
|---|---|---|---|---|
| pg-stored, round 2 | 299/s | 299/s | 300/s | 271 |
| **pg-stored, round 3** | **400/s** (p95 5 ms) | **300/s** (p95 4 ms) | **500/s** (p95 7 ms) | **415** |
| pg-batched-100, round 2 | 400/s | 400/s | 400/s | 314 |
| **pg-batched-100, round 3** | **599/s** (p95 30 ms) | **794/s** (p95 216 ms) | **600/s** (p95 34 ms) | **470** |

Lock / IO waits at the saturating step, `STORED`: 68% / 15% on capture at 500 rps, **84% / 5% on hot at 350 rps**, 77% / 7% on mixed at 600 rps. `BATCHED` stayed at 0% lock everywhere.

**What it shows:**
- **With real concurrency, the hot-account lock *is* `STORED`'s bottleneck.** Once 10 transactions run at once, they queue on the row locks of `psp_clearing`, `fee_revenue`, `tax_payable` and, with one merchant, `merchant_payable`. Near saturation 70–84% of active backends wait on a lock, and the one-merchant scenario saturates first, at ~300 rps. Rounds 1 and 2 never got there, because 3 request threads could not create the contention.
- **Group commit removes that contention.** One writer takes each balance row once per batch, so lock waits stay at 0%. It reached 600–800 rps, 1.5–2.6× `STORED` and the best result of all variants in the one-merchant scenario.
- **Bulk ingest rose too:** stored 271 → 415, batched 314 → 470 payments/s.
- **This is what makes sharding meaningful again.** With measured lock waits on `STORED`, splitting hot accounts (merchant rows included) is now a real alternative to group commit. Group commit already removes the waits without changing the chart of accounts.

**Followed up in round 4:** the trigger, `await` and batched inserts. The read-back after a write stays: it doubles as a check of what was stored.

## Round 2: stored balances vs group commit

Run `20261016-214831`, no other containers on the host.

| Variant | How a capture is written |
|---|---|
| `pg-stored` | One transaction per request: business row, journal entry, an `UPDATE balance` on each of its 4 accounts, a commit |
| `pg-batched-10` / `pg-batched-100` | Group commit: one writer runs up to 10 / 100 queued requests in one transaction. Every request writes its own rows, entry and postings; each account gets one `UPDATE` with the batch's summed delta, and there is one commit per batch |

**Highest holding rate** (lock / IO = share of active Postgres backends waiting on a row lock / on IO at that step):

| Variant | capture (100 merchants) | hot (1 merchant) | mixed (85/10/5) |
|---|---|---|---|
| pg-stored | 299/s (p95 213 ms; lock 0%, IO 52%) | 299/s (p95 198 ms; lock 0%, IO 43%) | 300/s (p95 27 ms; lock 0%, IO 6%) |
| pg-batched-10 | 250/s (p95 120 ms; lock 0%, IO 13%) | 250/s (p95 171 ms; lock 0%, IO 57%) | 200/s (p95 6 ms; lock 0%, IO 3%) |
| pg-batched-100 | **400/s** (p95 147 ms; lock 0%, IO 41%) | **400/s** (p95 52 ms; lock 0%, IO 66%) | **400/s** (p95 59 ms; lock 0%, IO 40%) |

**Bulk ingest and report latency:**

| Variant | seed: payments/s | report p50 / p95 @ 10k payments | report p50 / p95 @ 100k payments |
|---|---|---|---|
| pg-stored | 271 | 2 / 2 ms | 2 / 2 ms |
| pg-batched-10 | 262 | 2 / 3 ms | 2 / 2 ms |
| pg-batched-100 | 314 | 2 / 3 ms | 2 / 2 ms |

**What it showed** (read with round 3 in mind: here only 3 requests could be in flight):
- **No lock waits at all.** Lock waits were 0% in every step, but only because 3 request threads could not create contention, not because locks are free. Round 3 shows them.
- **Postgres looked idle:** 0.3–0.9 active backends at saturation. This was the clue that led to profiling: the app was not CPU-bound either, it was waiting on 3 threads.
- **Group commit with large batches lifts the ceiling by about a third:** 300 → 400 rps in every scenario, and 271 → 314 payments/s in bulk ingest. It saves exactly that commit and balance-update work: 100 requests share one commit and four balance `UPDATE`s instead of 100 and 400.
- **Small batches lose.** A single writer runs every request's statements one after another on one connection; `STORED` spreads them over the pool. With batches of 10, the commits saved do not pay for that lost parallelism; with 100, they do.
- **Under light load nothing changes.** A batch takes only what is already queued (`BATCH_MAX_WAIT_MS=0`), so at low rates batches hold one request and latency is the same as `STORED`.

## Round 1: six variants (history)

Run `20261016-150625`, with other projects' containers running.

| Variant | On capture / refund | Current-balance read | Fresh? |
|---|---|---|---|
| `pg-stored` | Postings, plus `UPDATE accounts SET balance = balance + x` on each account (row lock until commit) | Read the column | Exact |
| `pg-computed` | Postings only | `SUM` over all postings | Exact |
| `pg-snapshot` | Postings only | Snapshot + `SUM` of the postings since the last refresh | Exact |
| `pg-snapshot-only` | Postings only | Snapshot only, refreshed every 1 s | Stale by up to one refresh |
| `pg-sharded` | Like stored, with clearing, fee and tax accounts split into 8 sub-accounts | Folded back in the report | Exact |
| `tigerbeetle` | Business rows in Postgres, transfers in TigerBeetle (dual write) | `lookupAccounts` | Exact |

`pg-snapshot-only` was called `pg-batched` in that run. Nothing was batched; the name now belongs to group commit.

**Highest holding rate:**

| Variant | capture (100 merchants) | hot (1 merchant) | mixed (85/10/5) |
|---|---|---|---|
| pg-stored | 400/s (p95 23 ms) | 400/s (p95 8 ms) | 498/s (p95 161 ms) |
| pg-computed | 350/s (p95 25 ms) | 350/s (p95 46 ms) | none |
| pg-snapshot | 399/s (p95 223 ms) | 349/s (p95 221 ms) | 200/s (p95 35 ms) |
| pg-snapshot-only | 350/s (p95 15 ms) | 350/s (p95 44 ms) | 350/s (p95 65 ms) |
| pg-sharded | 350/s (p95 74 ms) | 350/s (p95 122 ms) | 300/s (p95 33 ms) |
| tigerbeetle | none (see below) | none | none |

**Bulk ingest and report latency:**

| Variant | seed: payments/s | report p50 / p95 @ 10k payments | report p50 / p95 @ 100k payments |
|---|---|---|---|
| pg-stored | 336 | 2 / 3 ms | 2 / 2 ms |
| pg-computed | 366 | 16 / 19 ms | 5489 / 9613 ms |
| pg-snapshot | 368 | 2 / 4 ms | 9 / 10 ms |
| pg-snapshot-only | 361 | 3 / 4 ms | 2 / 3 ms |
| pg-sharded | 323 | 2 / 3 ms | 2 / 3 ms |
| tigerbeetle | 233 | 6 / 11 ms | 7 / 22 ms |

Snapshot-only reads were up to 1004 ms stale, with up to 361 entries waiting for the next refresh.

**What it showed:**
- **`COMPUTED` fails on reads.** The report grows with history (5–10 s at 100k payments); with 5% reports in the mix it does not hold even 200 rps.
- **Snapshot + tail keeps reads exact but pays on every read.** It sums a few hundred tail entries per report, which halved the holding rate under the mixed load.
- **Snapshot-only reads are cheap but stale, and writes get no cheaper:** each request still commits on its own.
- **The sharding test was uninformative.**
  - Only clearing, fee and tax were split; the hot merchant's own `merchant_payable` row was not, so the `hot` scenario still had one shared row per payment.
  - More importantly, rounds 1 and 2 could not create lock contention (3 request threads); round 3 shows that `STORED` does contend once they are fixed.
  - Sharding is worth revisiting against round 3's lock waits.

**TigerBeetle** was measured twice, with the same TigerBeetle split of the budget (app 0.8 CPU / 384 MB, Postgres 0.4 CPU / 256 MB, TigerBeetle 0.8 CPU / 2432 MB):

| Run | capture | hot | mixed | seed | report @ 100k |
|---|---|---|---|---|---|
| `20261016-110258` | 200/s held (p95 8 ms) | 200/s held (p95 15 ms) | 200/s held (p95 54 ms) | 266/s | 6 / 8 ms |
| `20261016-150625` | stalled: 22/s, p95 152 s | 190/s, p95 874 ms | 193/s, p95 492 ms | 233/s | 7 / 22 ms |

Both runs failed at 400 rps. The second run's capture result is a stall, not a throughput limit: the app had 384 MB for the JVM and Postgres 0.4 CPU, because TigerBeetle allocates ~2.3 GB at start-up. On top of that, every request was a dual write: the business row and idempotency record in Postgres, plus a network round trip to TigerBeetle. Under this budget TigerBeetle cannot show what it is built for (thousands of transfers per second on a few accounts); see [ledger-comparison.md](ledger-comparison.md).

## What we kept

- **`STORED`** (default): one transaction per request; simple and exact. On this budget, on mains power, it holds 600–800 rps; the limit is the hot accounts' row locks.
- **`BATCHED`** (`BALANCE_MODE=BATCHED`, `BATCH_MAX_SIZE`, `BATCH_MAX_WAIT_MS`): group commit, still exact and synchronous; ~1000 rps at batch size 100, with no lock waits.

To reproduce:

```bash
perf/run-benchmarks.sh                       # pg-stored, pg-batched-10, pg-batched-100 (~75 min); perf/results/<time>/summary.md
perf/run-benchmarks.sh pg-stored pg-batched-100   # round 3 (~50 min)
```
