# Performance: ledger variants under one budget

How fast can the service take captures and refunds, and does the way balances are kept change that? Six ledger variants ran on the same code, data and resource budget. Two remain in the code (`STORED`, the default, and `BATCHED`); the other four were removed after the measurement. To rerun them, check out commit `0ffcf3f`.

## Setup

- **Budget:** 2 CPUs and 3 GB for the whole system under test, set with Docker limits ([`perf/compose.limits.yml`](../perf/compose.limits.yml)). The load generator runs outside the limit.

  | Variants | app | Postgres | TigerBeetle |
  |---|---|---|---|
  | Postgres variants | 1.0 CPU / 1280 MB | 1.0 CPU / 1792 MB (`shared_buffers` 448 MB) | — |
  | TigerBeetle | 0.8 CPU / 384 MB | 0.4 CPU / 256 MB | 0.8 CPU / 2432 MB |

  TigerBeetle allocates ~2.3 GB at start-up, so its variant had to squeeze the app and Postgres.
- **Host:** Docker Desktop 29.1.3 on Windows 11, a 16-CPU / 16 GB VM. Containers from other projects were running at the same time. The limits keep them from taking our CPUs, but not from sharing caches and memory bandwidth. Treat differences under ~50 rps as noise: an earlier run of the same harness put `STORED`'s capture limit between 200 and 400 rps, this one at 400.
- **Load:** [k6](https://grafana.com/docs/k6/) 2.3.0 ([`perf/k6/bench.js`](../perf/k6/bench.js)), driven by [`perf/run-benchmarks.sh`](../perf/run-benchmarks.sh). Each variant starts from empty volumes.

## Method

1. **Warm-up:** 15 s at 200 rps, not reported.
2. **Preload and report latency:** seed 10k payments, then measure `GET /v1/reports/balances` at 20 rps. Seed up to 100k payments and measure again. The seed's throughput doubles as a bulk-ingest number.
3. **Throughput ladders:** a constant arrival rate (open loop) of 200, 250, 300, 350, 400, 500… rps, 20 s per step, until a step fails. A step **holds** if it keeps up with the target (≥ 95%), with p95 < 250 ms and < 1% errors. There are three scenarios:
   - `capture`: payments spread over 100 merchants and 3 currencies;
   - `hot`: every payment to one merchant in one jurisdiction, the worst case for shared rows;
   - `mixed`: 85% captures, 10% partial refunds, 5% balance reports.
4. **Freshness:** during every step, a second k6 scenario polls `/v1/ledger/lag` twice a second. It records how stale a balance read is and how many entries wait for the next snapshot refresh. Its requests are excluded from the step's numbers.
5. **Consistency:** after each variant, `/v1/ledger/verify` and the report's integrity check must pass, and every merchant must reconcile. All six did.

**Why k6 and not Kotlin?** k6 is a load generator with a Go engine; only the scenarios are JavaScript. It runs from one Docker image with no build step, and its arrival-rate executor starts requests on schedule even when the service slows down, so latency is not hidden by coordinated omission. The Kotlin-native equivalent is Gatling (Java/Kotlin DSL, Gradle plugin). A home-made loop on coroutines would measure the wrong thing.

## The variants

| Variant | On capture / refund | Current-balance read | Fresh? |
|---|---|---|---|
| `pg-stored` | Postings, plus `UPDATE accounts SET balance = balance + x` on each account (row lock until commit) | Read the column | Exact |
| `pg-computed` | Postings only | `SUM` over all postings | Exact |
| `pg-snapshot` | Postings only | Snapshot + `SUM` of the postings since the last refresh | Exact |
| `pg-batched` | Postings only | Snapshot only, refreshed every 1 s | Stale by up to one refresh |
| `pg-sharded` | Like stored, with clearing, fee and tax accounts split into 8 sub-accounts | Folded back in the report | Exact |
| `tigerbeetle` | Business rows in Postgres, transfers in TigerBeetle (dual write) | `lookupAccounts` | Exact |

## Results

Run `20261016-150625`.

**Highest holding rate** (keeps up, p95 < 250 ms, errors < 1%):

| Variant | capture (100 merchants) | hot (1 merchant) | mixed (85/10/5) |
|---|---|---|---|
| pg-stored | 400/s (p95 23 ms) | 400/s (p95 8 ms) | 498/s (p95 161 ms) |
| pg-computed | 350/s (p95 25 ms) | 350/s (p95 46 ms) | none |
| pg-snapshot | 399/s (p95 223 ms) | 349/s (p95 221 ms) | 200/s (p95 35 ms) |
| pg-batched | 350/s (p95 15 ms) | 350/s (p95 44 ms) | 350/s (p95 65 ms) |
| pg-sharded | 350/s (p95 74 ms) | 350/s (p95 122 ms) | 300/s (p95 33 ms) |
| tigerbeetle | none | none | none |

**Bulk ingest and report latency:**

| Variant | seed: payments/s | report p50 / p95 @ 10k payments | report p50 / p95 @ 100k payments |
|---|---|---|---|
| pg-stored | 336 | 2 / 3 ms | 2 / 2 ms |
| pg-computed | 366 | 16 / 19 ms | 5489 / 9613 ms |
| pg-snapshot | 368 | 2 / 4 ms | 9 / 10 ms |
| pg-batched | 361 | 3 / 4 ms | 2 / 3 ms |
| pg-sharded | 323 | 2 / 3 ms | 2 / 3 ms |
| tigerbeetle | 233 | 6 / 11 ms | 7 / 22 ms |

**Freshness** (worst step):

| Variant | read lag max | entries waiting for the snapshot, max |
|---|---|---|
| pg-snapshot | 0 ms (reads add the tail) | 394 |
| pg-batched | 1004 ms | 361 |
| all others | 0 ms | 0 |

TigerBeetle's steps, all at 200 rps:
- capture achieved 22 rps, with p95 152 s;
- hot achieved 190 rps, with p95 874 ms;
- mixed achieved 193 rps, with p95 492 ms.

## What it means

- **The limit is CPU per request, not balance locks.** Every Postgres variant tops out at 350–500 rps on 2 CPUs, within noise of each other, including on a single hot merchant. Taking the balance `UPDATE` out of the write path (computed, snapshot, batched) or spreading it over sub-accounts (sharded) buys nothing at this size. Each request spends its time in HTTP, JSON, the idempotency fingerprint and 6–8 statements, not waiting for a row lock. A hot-account row lock is held for about the length of a commit (~1–2 ms), so it serialises somewhere around 500–1000 entries/s per account. That is above what 2 CPUs can feed.
- **`COMPUTED` fails on reads.** The report grows with history: 5–10 s at 100k payments. With 5% reports in the mix, it does not hold even 200 rps.
- **`SNAPSHOT` keeps reads exact but pays on every read.** Each report sums the tail since the last refresh, a few hundred entries. That is cheap for one report, but under a mixed load it halved the holding rate.
- **`BATCHED` makes reads constant-cost and lock-free, at the price of staleness:** a balance read is up to one refresh old (≤ 1 s here). That is fine for reports and dashboards. It is not fine for decisions that need the balance at that moment, such as payout or refund limits. For those, the write path still has the exact ledger, and an exact check can read the snapshot plus the tail.
- **Sharding hot accounts adds rows and work without removing a bottleneck we have.** It belongs to a system that has measured hot-row contention.
- **TigerBeetle lost here because of the budget, not its engine.** Its static memory left the app and Postgres 0.7 GB and 1.2 CPUs between them. The service still writes the business record and its idempotency row to Postgres, so every request became a dual write: Postgres and a network round trip to TigerBeetle. The 152 s p95 on capture is that starved stack falling behind, not a TigerBeetle limit. On dedicated hardware, at thousands of transfers per second on a few accounts, the picture would differ (see [ledger-comparison.md](ledger-comparison.md)).

## What we kept

- **`STORED`** (default): exact, the fastest overall, constant-cost reads.
- **`BATCHED`** (`BALANCE_MODE=BATCHED`, `BALANCE_REFRESH_MS`): the documented next step if writes ever contend on hot accounts or reads get heavy. `/v1/ledger/lag` reports its staleness.

To reproduce:

```bash
perf/run-benchmarks.sh                       # pg-stored and pg-batched; results in perf/results/<time>/summary.md
```
