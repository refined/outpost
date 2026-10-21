# Ledger platforms: comparison and decision

The brief allows an off-the-shelf ledger (Formance, TigerBeetle) or our own. I chose to implement the accounting mechanics first to understand the domain, then explored alternatives to test that choice. This page records the architectural comparison and the TigerBeetle integration. The separate [performance study](performance.md) records experiments on balance storage and application throughput.

## What the ledger has to do here

| Need | Why |
|---|---|
| **Multi-leg entries, all or nothing** | A capture splits into 3–4 legs (clearing, tax, fee, merchant); a refund reverses some of them |
| **Atomic with the business record** | The payment row, its idempotency fingerprint and its journal entry must commit together, or a retry can double-post or lose money |
| **Exact current balances** | The report should reflect committed captures and refunds; refund limits are checked against the locked payment and its refunds |
| **Idempotent posting** | Capture and refund events are PSP webhooks and are retried |
| **History** | "What did we owe on date X" (reports `asOf`), and an audit trail |
| **Many small accounts** | One payable per merchant and per tax jurisdiction, per currency; no FX |
| **Small operational footprint** | Local startup needs only Docker; the brief sets no throughput target or production traffic estimate |

## Candidates and evaluation scope

| Option | What it offers | Trade-off for this service | How evaluated |
|---|---|---|---|
| **Own Postgres ledger** | Accounts, multi-leg journal entries, exact balances and effective-time reports in our database | We own accounting validation, concurrency and maintenance; hot accounts take row locks | Implemented, tested and benchmarked through several iterations |
| **TigerBeetle 0.17.9** | A financial database with atomic linked transfers and idempotent submission | Our business records still need Postgres; two stores need coordinated recovery, and the tested configuration used most of our memory budget | Integrated, tested and benchmarked at stage 1; implementation then removed |
| **Formance Ledger** | A ledger API, Numscript transaction scripting and idempotency keys | A separate service; payment metadata in Postgres would need coordination with ledger writes | Documentation review only; no implementation or benchmark |

Modern Treasury and Blnk were also considered through documentation (links below), but neither was integrated or measured. There is no performance ranking of these products here. The five Postgres balance strategies in the experiment below are variants of our own implementation, not five external ledger products.

## What we learned by actually integrating TigerBeetle

We built `TigerBeetleLedger` as a second implementation of the ledger interface, selectable with `LEDGER_ENGINE=TIGERBEETLE`, ran the same money-flow tests and the benchmark on it, and then removed it (the code is in commit `0ffcf3f`). Balances matched the Postgres ledger exactly, and the full demo dataset reconciled merchant by merchant. Things that only showed up while doing it:

- **Dual write is real.** Payments and refunds still live in Postgres. In the prototype, transfers were sent to TigerBeetle before the Postgres commit. If that commit failed, the stores could disagree. Deterministic transfer IDs made retries safe, but recovery still depended on a retry arriving. A production integration would need durable recovery, such as an outbox with retry and reconciliation. The console's merchant check detects discrepancies; it does not repair them.
- **Linked transfers and retries.** Re-sending a linked chain answers `exists` for the first transfer and `linked_event_failed` for the rest (the chain is atomic). Idempotency handling has to recognise that combination; treating `linked_event_failed` as an error would turn every retry into a 500.
- **Multi-leg entries need pairing.** A capture (one debit, three credits) becomes three linked two-account transfers. Easy, but the journal entry as one object no longer exists in the ledger — browsing "an entry" means reassembling transfers.
- **No DNS names.** The client accepts IP addresses only; we resolve the host before connecting.
- **Memory affected the experiment.** The tested TigerBeetle configuration allocated ~2.3 GB at start-up with a 256 MiB grid cache. Under our 3 GB budget it left ~0.7 GB for the app and Postgres together; this is a measurement of that configuration, not a minimum for every deployment.
- **History is opt-in.** As-of balances need the `history` flag on accounts (more storage); without it, our `asOf` report answers 501 in TigerBeetle mode.

## Measurements and their limits

2 CPUs and 3 GB for the whole system under test, every variant from its own commit on the same machine; a step holds with p95 under 200 ms. Full method and numbers: [performance.md](performance.md).

These are end-to-end service measurements, including HTTP and business-record writes. TigerBeetle was measured only at stage 1, before the request-thread fix and later Postgres optimizations. The 20-second steps, unequal preload sizes and lack of repeats make the results exploratory. Comparing stage 8 directly with stage 1 does not establish that our ledger engine is faster than TigerBeetle. Later code refactors have not been rebenchmarked.

| Variant | Stage | Holds: capture / one merchant / mixed | Report p95 (100k payments unless noted) | Read staleness |
|---|---|---|---|---|
| Postgres, stored balances, final | 8 | 900 / 700 / 800 rps | 3 ms (10k payments) | none |
| Postgres, group commit (batches of 100), set-based writes | 8 | 5200 / 5400 / 3500 rps | 3 ms (10k payments) | none |
| Postgres, group commit, statements per item | 7 | 1000 / 1100 / 1000 rps | 2 ms | none |
| Postgres, stored balances, at the start | 1 | 200 / < 300 / 300 rps | 2 ms | none |
| Postgres, summed on read | 1 | 300 / 300 / 100 rps | 9.1 s | none |
| Postgres, snapshot + tail | 1 | 300 / 400 / 200 rps | 10 ms | none |
| Postgres, snapshot only (1 s) | 1 | 400 / 200 / 300 rps | 3 ms | ≤ 1 s |
| Postgres, stored, 8 sub-accounts | 1 | 300 / 400 / 400 rps | 2 ms | none |
| TigerBeetle (+ Postgres for business rows) | 1 | 200 / 300 / 200 rps; p95 580 ms at 300 captures/s | 28 ms | none |

Every stage-1 variant was capped near 300 req/s by blocking JDBC on Ktor's 3 request threads, found later by profiling. Fixing that, and dropping a deferred balance trigger, took the Postgres ledger to the final numbers.
- Once requests really run concurrently, `STORED` saturates on the hot accounts' row locks: up to 80% of active Postgres backends wait on a lock in the one-merchant scenario.
- Group commit takes each balance row once per batch and keeps lock waits at 0%.
- TigerBeetle's ~2.3 GB of static memory starved the app and Postgres under this budget, and the dual write added a network round trip to every request.

## Decision

**Our own ledger on Postgres, with stored balances.** Learning the mechanics motivated the implementation; keeping payment records and postings in one transaction, a small deployment, and coverage of the required flows motivated keeping it. Among the architectures evaluated here, it is the one that shares a local transaction with our payment records. The benchmark informed how we implemented it, rather than proving a universal performance advantage.

The cost is maintaining our own accounting code and tests. Entry balance is checked in the shared application write path, not by a database constraint; database triggers protect history from updates and deletes. Balance reconciliation detects drift against postings, but does not independently prove every journal entry balances. The project does not implement the operational and recovery features of a dedicated replicated ledger cluster.

- **Optional group commit:** `BALANCE_MODE=BATCHED` amortizes commits and balance updates, with captures written as multi-row inserts. Both modes answer after commit. In the stage-8 measurements, capture throughput was 5200 vs 900 rps (~5.8×), and mixed throughput 3500 vs 800 (~4.4×), compared with our own `STORED` mode. This costs a queue, a single writer and per-item retry handling.
- **Removed after measuring:**
  - summed-on-read balances: reads grow with history;
  - snapshot + tail: the read cost was paid on every report;
  - snapshot only: stale reads, and writes no cheaper;
  - hot-account sub-accounts: no contention to remove when they were measured; group commit now removes it without changing the chart of accounts;
  - the TigerBeetle engine.
- **When to revisit TigerBeetle:** when production requirements justify a dedicated ledger cluster and cross-store recovery. Evaluate it with the request-thread bottleneck removed, a suitable resource allocation, equivalent guarantees and repeated longer runs. The brief supplies no traffic target against which to make that production decision.
- **Formance / SaaS:** worth a look when ledger features (Numscript flows, multi-tenant books, a hosted audit trail) matter more than single-transaction atomicity with our own records.

## Sources

- TigerBeetle: [Java client README (0.17.9)](https://github.com/tigerbeetle/tigerbeetle/blob/0.17.9/src/clients/java/README.md), [Docker deployment](https://github.com/tigerbeetle/tigerbeetle/blob/0.17.9/docs/operating/deploying/docker.md), [architecture](https://docs.tigerbeetle.com/about/architecture), [CDC](https://docs.tigerbeetle.com/operating/cdc/)
- TigerBeetle semantics: [linked events](https://docs.tigerbeetle.com/coding/linked-events/), [reliable transaction submission](https://docs.tigerbeetle.com/coding/reliable-transaction-submission/)
- Formance: [Ledger repository and feature overview](https://github.com/formancehq/ledger)
- Modern Treasury: [handling concurrency](https://docs.moderntreasury.com/ledgers/docs/handle-concurrency), [optimistic locking](https://www.moderntreasury.com/journal/designing-ledgers-with-optimistic-locking)
- Blnk: [balances](https://www.mintlify.com/blnkfinance/blnk/concepts/balances), [snapshots](https://www.mintlify.com/blnkfinance/blnk/api/balances/snapshots)
