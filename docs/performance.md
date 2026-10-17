# Performance: ledger variants under one budget

How fast can the service take captures and refunds, and does the way balances are kept change that? Everything below ran on the same code, data and resource budget, in three rounds, newest first:

1. **Round 3:** the two modes in the code after profiling found the real limit: database work blocking Ktor's 3 request threads. Fixed with an IO dispatcher.
2. **Round 2:** `STORED` (default) and `BATCHED` (group commit), with lock and IO waits sampled from Postgres; still limited by those 3 threads.
3. **Round 1:** six earlier variants. Four were removed afterwards; their code is at commit `0ffcf3f`.

## Setup

- **Budget:** 2 CPUs and 3 GB for the whole system under test, set with Docker limits ([`perf/compose.limits.yml`](../perf/compose.limits.yml)): app 1.0 CPU / 1280 MB, Postgres 1.0 CPU / 1792 MB (`shared_buffers` 448 MB). A limit is a CPU-time quota (cgroups), not pinned cores. The load generator runs outside the limit.
- **Host:** Docker Desktop 29.1.3 on Windows 11, a 16-CPU / 16 GB VM.
  - **Round 3** ran next to the (mostly idle) sharetravel containers.
  - **Round 2** ran with no other containers.
  - **Round 1** ran next to other projects' containers.
  - Treat differences under ~50 rps as noise. `STORED`'s capture limit came out at 200–400, 400 and 300 rps in three runs.
- **Load:** [k6](https://grafana.com/docs/k6/) 2.3.0 ([`perf/k6/bench.js`](../perf/k6/bench.js)), driven by [`perf/run-benchmarks.sh`](../perf/run-benchmarks.sh). Each variant starts from empty volumes.

## Method

1. **Warm-up:** 15 s at 200 rps, not reported.
2. **Preload and report latency:** seed 10k payments, then measure `GET /v1/reports/balances` at 20 rps; seed up to 100k payments and measure again. The seed is closed-loop (24 virtual users), so its throughput doubles as a bulk-ingest number.
3. **Throughput ladders:** a constant arrival rate (open loop) of 200, 250, 300, 350, 400, 500… rps, 20 s per step, until a step fails. A step **holds** if it keeps up with the target (≥ 95%), with p95 < 250 ms and < 1% errors. There are three scenarios:
   - `capture`: payments spread over 100 merchants and 3 currencies;
   - `hot`: every payment to one merchant in one jurisdiction, the worst case for shared rows;
   - `mixed`: 85% captures, 10% partial refunds, 5% balance reports.
4. **Waits (round 2):** during every step, `pg_stat_activity` is sampled every 200 ms. The result is the share of active Postgres backends waiting on a row lock, and on IO (mostly the WAL flush at commit).
5. **Consistency:** after each variant, `/v1/ledger/verify` and the report's integrity check must pass, and every merchant must reconcile. All variants in both rounds did.

**Why k6 and not Kotlin?**
- k6 is a load generator with a Go engine; only the scenarios are JavaScript.
- It runs from one Docker image with no build step.
- Its arrival-rate executor starts requests on schedule even when the service slows down, so latency is not hidden by coordinated omission.
- The Kotlin-native equivalent is Gatling (Java/Kotlin DSL, Gradle plugin). A home-made loop on coroutines would measure the wrong thing.

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
- **Group commit removes that contention.** One writer takes each balance row once per batch, so lock waits stay at 0%. It reached 600–800 rps, 1.5–2.6× `STORED` and the best single-merchant result of all variants.
- **Bulk ingest rose too:** stored 271 → 415, batched 314 → 470 payments/s.
- **This is what makes sharding meaningful again.** With measured lock waits on `STORED`, splitting hot accounts (merchant rows included) is now a real alternative to group commit. Group commit already removes the waits without changing the chart of accounts.

**Not done yet** (each was measured in the profile, none needed at this scale):
- check an entry's balance once instead of on every posting (the deferred trigger runs 5 times per capture, 25% of SQL time);
- build the capture response without re-reading the payment;
- batch the SQL itself (multi-row inserts per batch).

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

- **`STORED`** (default): one transaction per request; simple and exact. On this budget it holds 300–500 rps; the limit is the hot accounts' row locks.
- **`BATCHED`** (`BALANCE_MODE=BATCHED`, `BATCH_MAX_SIZE`, `BATCH_MAX_WAIT_MS`): group commit, still exact and synchronous; 600–800 rps at batch size 100, with no lock waits.

To reproduce:

```bash
perf/run-benchmarks.sh                       # pg-stored, pg-batched-10, pg-batched-100 (~75 min); perf/results/<time>/summary.md
perf/run-benchmarks.sh pg-stored pg-batched-100   # round 3 (~50 min)
```
