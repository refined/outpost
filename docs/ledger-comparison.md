# Ledger platforms: comparison and decision

The brief allows an off-the-shelf ledger (Formance, TigerBeetle) or our own. This page compares the options against what this service actually needs, using our own measurements where we have them (see [performance.md](performance.md)), and records the decision.

## What the ledger has to do here

| Need | Why |
|---|---|
| **Multi-leg entries, all or nothing** | A capture splits into 3–4 legs (clearing, tax, fee, merchant); a refund reverses some of them |
| **Atomic with the business record** | The payment row, its idempotency fingerprint and its journal entry must commit together, or a retry can double-post or lose money |
| **Exact current balances** | Decisions (refund limits today, payouts later) need the balance at the moment of the decision, not an eventually consistent one |
| **Idempotent posting** | Capture and refund events are PSP webhooks and are retried |
| **History** | "What did we owe on date X" (reports `asOf`), and an audit trail |
| **Many small accounts** | One payable per merchant and per tax jurisdiction, per currency; no FX |
| **Small operational footprint** | Docker-only, one small team; the system design estimate is ~2–3 TPS average and low hundreds at peak |

## Candidates

| | **Own ledger on Postgres** (this repo) | **TigerBeetle** 0.17.9 | **Formance Ledger** v3 | **Modern Treasury Ledgers** | **Blnk** |
|---|---|---|---|---|---|
| What it is | Tables + constraints in the service's own Postgres | Purpose-built financial database (Zig), single-leader replicated state machine | Ledger service (Go): Raft + deterministic state machine + Pebble storage | SaaS ledger API | Open-source ledger service (Go) on Postgres + Redis |
| Data model | Accounts, journal entries, signed postings (any number of legs) | Accounts (debits/credits posted/pending) and two-account transfers; multi-leg = linked transfers | Transactions with postings (source → destination, asset); scripts in Numscript | Accounts, entries, transactions; pending / posted / available balances | Balances, transactions, inflight amounts |
| Where balances live | Running balance column, updated in the same transaction (or summed on read) | On the account, updated by every transfer | Volumes in the authoritative state; read indexes built asynchronously | On the account, with a `lock_version` | On the balance record; snapshots for history |
| Atomic with our business data | **Yes**: one Postgres transaction | No: separate system → dual write (see below) | No: separate service → dual write | No: external API → dual write | No: separate service → dual write |
| Concurrency / hot accounts | Row lock per account until commit; mitigations: sub-account sharding, batching | No locks: transfers applied sequentially by one thread in batches (up to ~8k per batch) | Applied by the state machine after consensus | Optimistic (`lock_version`) | Per-balance queueing (per their docs/secondary sources) |
| Balance limits | In code / conditional `UPDATE … WHERE balance >= x` | Built in: `debits_must_not_exceed_credits` and the mirror flag | Numscript (`allowing overdraft` etc.) | Balance-limited entries | Overdraft settings |
| Idempotency | Natural keys + request fingerprint, enforced by unique constraints | Client-chosen transfer ids: a retry returns `exists` | Idempotency keys | Idempotency keys | References |
| History / as-of | Sum of postings up to a time (effective time) | Needs the `history` account flag; we did not enable it | Log replay / indexes | Effective-time balances | Snapshots |
| Operations | Nothing extra: the service's Postgres | One more stateful service; needs io_uring (`seccomp=unconfined` in Docker, for the client too); **~2.3 GB allocated up front** (1.45 GB with `--development`) | One more stateful cluster (Raft) | None (managed), but a vendor and per-volume pricing | Postgres + Redis + the service |
| Licence | — | Apache-2.0 | No licence file on the current `main` (v3) — check before adopting | Commercial | Apache-2.0 |

## What we learned by actually integrating TigerBeetle

We built `TigerBeetleLedger` as a second implementation of the ledger interface, selectable with `LEDGER_ENGINE=TIGERBEETLE`, ran the same money-flow tests and the benchmark on it, and then removed it (the code is in commit `0ffcf3f`). Balances matched the Postgres ledger exactly, and the full demo dataset reconciled merchant by merchant. Things that only showed up while doing it:

- **Dual write is real.** Payments and refunds still live in Postgres. Transfers are sent to TigerBeetle *inside* the Postgres transaction, just before commit. If the commit then fails, TigerBeetle has transfers that Postgres does not; the retried webhook repairs it (deterministic ids → `exists`), but an event that is never retried leaves an orphan. We need a reconciliation job either way, and the merchant "ledger check" in the console is exactly that.
- **Linked transfers and retries.** Re-sending a linked chain answers `exists` for the first transfer and `linked_event_failed` for the rest (the chain is atomic). Idempotency handling has to recognise that combination; treating `linked_event_failed` as an error would turn every retry into a 500.
- **Multi-leg entries need pairing.** A capture (one debit, three credits) becomes three linked two-account transfers. Easy, but the journal entry as one object no longer exists in the ledger — browsing "an entry" means reassembling transfers.
- **No DNS names.** The client accepts IP addresses only; we resolve the host before connecting.
- **Memory is static.** TigerBeetle allocates its memory at start-up (~2.3 GB with a 256 MiB grid cache). Under our 3 GB budget it leaves ~0.7 GB for the app and Postgres together.
- **History is opt-in.** As-of balances need the `history` flag on accounts (more storage); without it, our `asOf` report answers 501 in TigerBeetle mode.

## Measured under the same budget

2 CPUs and 3 GB for the whole system under test. Full method and numbers: [performance.md](performance.md). Round 1 ran next to other containers, round 2 on a quiet host; compare variants within a round.

| Variant | Round | Highest holding rate: capture / hot merchant / mixed | Balance report @ 100k payments (p95) | Read staleness |
|---|---|---|---|---|
| Postgres, stored balances | 2 | 299 / 299 / 300 rps | 2 ms | none |
| Postgres, group commit, batches of 100 | 2 | 400 / 400 / 400 rps | 2 ms | none |
| Postgres, stored balances | 1 | 400 / 400 / 498 rps | 2 ms | none |
| Postgres, snapshot only (1 s) | 1 | 350 / 350 / 350 rps | 3 ms | ≤ 1 s |
| Postgres, snapshot + tail | 1 | 399 / 349 / 200 rps | 10 ms | none |
| Postgres, summed on read | 1 | 350 / 350 / — | 9.6 s | none |
| Postgres, stored, 8 sub-accounts | 1 | 350 / 350 / 300 rps | 3 ms | none |
| TigerBeetle (+ Postgres for business rows) | 1 (twice) | 200 / 200 / 200 rps in one run; stalled in the other | 8–22 ms | none |

Row-lock waits were 0% at every step, even with every payment on one merchant. The ceiling is the app's CPU and the per-request commit, which is why group commit helps and sub-accounts did not. TigerBeetle's ~2.3 GB of static memory starved the app and Postgres under this budget, and the dual write added a network round trip to every request.

## Decision

**Our own ledger on Postgres, with stored balances.** It is the only option where the payment, its idempotency record and its journal entry commit in one transaction, and it adds nothing to operate.

- **Next step when throughput matters:** `BALANCE_MODE=BATCHED`, kept in the code. Group commit runs queued requests in batches: one transaction, one commit and one balance update per account per batch, with a savepoint per request. Balances stay exact and synchronous; at batch size 100 it lifted the ceiling from ~300 to ~400 rps on this budget. The step after that is batching the SQL itself (multi-row inserts per batch).
- **Removed after measuring:**
  - summed-on-read balances: reads grow with history;
  - snapshot + tail: the read cost was paid on every report;
  - snapshot only: stale reads, and writes no cheaper;
  - hot-account sub-accounts: there was no lock contention to remove;
  - the TigerBeetle engine.
- **When to revisit TigerBeetle:** sustained thousands of transfers per second concentrated on a few accounts (a single merchant or a single clearing account), with a budget for a dedicated, replicated TigerBeetle cluster and an outbox/reconciliation job for the dual write. That is far beyond this service's few TPS average and low hundreds at peak.
- **Formance / SaaS:** worth a look when ledger features (Numscript flows, multi-tenant books, a hosted audit trail) matter more than single-transaction atomicity with our own records.

## Sources

- TigerBeetle: [Java client README (0.17.9)](https://github.com/tigerbeetle/tigerbeetle/blob/0.17.9/src/clients/java/README.md), [Docker deployment](https://github.com/tigerbeetle/tigerbeetle/blob/0.17.9/docs/operating/deploying/docker.md), [architecture](https://docs.tigerbeetle.com/about/architecture), [CDC](https://docs.tigerbeetle.com/operating/cdc/)
- Formance: [Ledger v3 architecture overview](https://github.com/formancehq/ledger/blob/main/docs/technical/architecture/overview.md)
- Modern Treasury: [handling concurrency](https://docs.moderntreasury.com/ledgers/docs/handle-concurrency), [optimistic locking](https://www.moderntreasury.com/journal/designing-ledgers-with-optimistic-locking)
- Blnk: [balances](https://www.mintlify.com/blnkfinance/blnk/concepts/balances), [snapshots](https://www.mintlify.com/blnkfinance/blnk/api/balances/snapshots)
