# Performance: ledger variants under one budget

How fast can the service take captures and refunds, and does the way balances are kept change that? Everything below ran on the same code, data and resource budget, in two rounds:

1. **Round 2:** the two modes in the code, `STORED` (default) and `BATCHED` (group commit), with lock and IO waits sampled from Postgres.
2. **Round 1:** six earlier variants. Four were removed afterwards; their code is at commit `0ffcf3f`.

## Setup

- **Budget:** 2 CPUs and 3 GB for the whole system under test, set with Docker limits ([`perf/compose.limits.yml`](../perf/compose.limits.yml)): app 1.0 CPU / 1280 MB, Postgres 1.0 CPU / 1792 MB (`shared_buffers` 448 MB). A limit is a CPU-time quota (cgroups), not pinned cores. The load generator runs outside the limit.
- **Host:** Docker Desktop 29.1.3 on Windows 11, a 16-CPU / 16 GB VM.
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

**What it shows:**
- **Row locks are never the bottleneck.** Lock waits were 0% in every step of every variant, including every payment on one merchant at saturation. That is why splitting hot accounts (round 1) could not help.
- **Postgres is mostly idle.** Even at saturation, `STORED` had 0.3–0.9 active Postgres backends on average. The time it does spend is largely IO: the WAL flush at commit, 30–50% of active samples near the limit. The rest of the time goes into the app: HTTP, JSON, JDBC round trips on 1 CPU.
- **Group commit with large batches lifts the ceiling by about a third:** 300 → 400 rps in every scenario, and 271 → 314 payments/s in bulk ingest. It saves exactly that commit and balance-update work: 100 requests share one commit and four balance `UPDATE`s instead of 100 and 400.
- **Small batches lose.** A single writer runs every request's statements one after another on one connection; `STORED` spreads them over the pool. With batches of 10, the commits saved do not pay for that lost parallelism; with 100, they do.
- **Under light load nothing changes.** A batch takes only what is already queued (`BATCH_MAX_WAIT_MS=0`), so at low rates batches hold one request and latency is the same as `STORED`.

**What would lift it further:**
- **Batch the SQL itself, not only the commit:** one multi-row `INSERT` per table per batch instead of ~8 statements per request. This is what TigerBeetle does natively.
- **Give the app more of the budget:** the database is underused.

Neither is needed at this service's scale.

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
  - More importantly, round 2's lock sampling shows there was no lock contention to remove.
  - Sharding belongs to a system that has measured lock waits.

**TigerBeetle** was measured twice, with the same TigerBeetle split of the budget (app 0.8 CPU / 384 MB, Postgres 0.4 CPU / 256 MB, TigerBeetle 0.8 CPU / 2432 MB):

| Run | capture | hot | mixed | seed | report @ 100k |
|---|---|---|---|---|---|
| `20261016-110258` | 200/s held (p95 8 ms) | 200/s held (p95 15 ms) | 200/s held (p95 54 ms) | 266/s | 6 / 8 ms |
| `20261016-150625` | stalled: 22/s, p95 152 s | 190/s, p95 874 ms | 193/s, p95 492 ms | 233/s | 7 / 22 ms |

Both runs failed at 400 rps. The second run's capture result is a stall, not a throughput limit: the app had 384 MB for the JVM and Postgres 0.4 CPU, because TigerBeetle allocates ~2.3 GB at start-up. On top of that, every request was a dual write: the business row and idempotency record in Postgres, plus a network round trip to TigerBeetle. Under this budget TigerBeetle cannot show what it is built for (thousands of transfers per second on a few accounts); see [ledger-comparison.md](ledger-comparison.md).

## What we kept

- **`STORED`** (default): one transaction per request; simple and exact. Its ceiling here is the app's CPU and the per-request commit, not locks.
- **`BATCHED`** (`BALANCE_MODE=BATCHED`, `BATCH_MAX_SIZE`, `BATCH_MAX_WAIT_MS`): group commit, still exact and synchronous; +33% throughput at batch size 100 on this budget.

To reproduce:

```bash
perf/run-benchmarks.sh                       # pg-stored, pg-batched-10, pg-batched-100 (~75 min); perf/results/<time>/summary.md
```
