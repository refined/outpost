# Mini MoR Ledger Service

[![CI](https://github.com/refined/outpost/actions/workflows/ci.yml/badge.svg)](https://github.com/refined/outpost/actions/workflows/ci.yml)

## What it is and why

A **Merchant of Record (MoR)** sells a merchant's product in its own name. It:
- collects the payment from the customer;
- is liable for the sales tax/VAT;
- keeps its fee;
- owes the rest to the merchant.

Every payment is split between three parties, and refunds and retried webhooks make it easy to lose or double-count a cent.

This service is the MoR's **ledger**: the source of truth for who is owed what. It can:

- **record captured payments**: split each one into tax, the MoR fee and the merchant's share;
- **record refunds**, full or partial, and reverse exactly the right amounts;
- **report balances**:
  - how much the MoR owes each tax authority;
  - what each merchant's balance with the MoR is, now or as of any past date;
- **handle several currencies**, each kept separately;
- **show what each merchant did**: volume, refunds, fees, tax and the running balance, in a web console.

The ledger is our own double-entry ledger on Postgres. Six ways of keeping balances, TigerBeetle among them, were benchmarked under the same resource budget before choosing ([ledger comparison](docs/ledger-comparison.md), [performance](docs/performance.md)).

## How it works

```mermaid
flowchart LR
    PSP["PSP / checkout<br/>(capture and refund events)"] --> API
    UI["Demo console<br/>/ui"] --> API
    SW["Swagger<br/>/docs"] --> API
    subgraph service["MoR ledger service"]
        API["REST API /v1"] --> PAY["Payments<br/>split, idempotency, refund limits"]
        API --> REP["Reports & analytics"]
        PAY --> LED["Ledger<br/>journal + account balances"]
        REP --> LED
    end
    PAY --> DB[("PostgreSQL<br/>payments, refunds,<br/>ledger")]
    LED --> DB
```

There are three ways in:

- **REST API** (`/v1`): what a payment provider's webhooks or a checkout would call.
- **Console** (`/ui/`), one page per question:
  - **Overview**: what the MoR owes now, and the activity in a period: captured vs refunded over time, where the money went (a flow diagram), tax by jurisdiction, top merchants;
  - **Merchants**: every merchant's volume, refunds, refund rate, fees, tax and balance, each checked against the ledger;
  - **Merchant**: one merchant's activity and running balance, its payments (refund from there) and its journal entries;
  - **Ledger**: every account, the journal, and a reconciliation of stored balances against postings;
  - **Simulate**: send capture/refund events by hand or run a scripted scenario.
- **Swagger** (`/docs/`): the interactive API spec.

### The API

The three operations the service exists for. Money is in integer **minor units** (cents); rates are decimal strings.

**Capture a payment**: `POST /v1/payments`

```json
{"paymentId": "pay_1001", "merchantId": "acme", "amount": 12100, "currency": "EUR",
 "taxRate": "0.21", "taxJurisdiction": "NL"}
```

The response returns the split: `taxAmount: 2100`, `feeAmount: 605`, `merchantAmount: 9395`.

**Refund a payment**: `POST /v1/payments/{paymentId}/refunds`

```json
{"refundId": "ref_1001_a", "amount": 6050}
```

Omit `amount` for a full refund. A refund larger than what remains is rejected (`422`).

**Balances report**: `GET /v1/reports/balances` (optionally `?asOf=2026-10-01T00:00:00Z`)

```json
{
  "taxAuthorities": [{"jurisdiction": "NL", "currency": "EUR", "amountOwed": 1050}],
  "merchants":      [{"merchantId": "acme", "currency": "EUR", "balance": 4395}],
  "mor":            [{"currency": "EUR", "feeRevenue": 605, "pspClearing": 6050}],
  "integrity":      {"balanced": true, "unbalancedCurrencies": []}
}
```

`paymentId` and `refundId` are the external event ids, so a retried event is safe: the same request returns the stored result (`200`), and the same id with different data is rejected (`409`). Errors look like `{"error": "<code>", "message": "..."}`. The read-only helper endpoints used by the console are listed in Swagger.

## How the ledger works

The ledger is **double-entry**: every event is one journal entry whose debits equal its credits.

### Accounts

There are four kinds of account, one set per currency:

| Account | Holds |
|---|---|
| `psp_clearing` | Money collected from customers (held at the payment provider) |
| `tax_payable:{country}` | Tax owed to that country's authority |
| `merchant_payable:{merchant}` | What the MoR owes the merchant |
| `fee_revenue` | The MoR's fee |

### Capture

The capture carries what the customer paid, **tax included**, and the tax rate. It becomes one balanced entry:

```
Customer paid €121.00 in NL, VAT 21%, MoR fee 5%

  Dr psp_clearing             121.00
  Cr tax_payable:NL                     21.00   = 121.00 × 0.21 / 1.21
  Cr fee_revenue                         6.05   = 5% of 121.00
  Cr merchant_payable:acme              93.95   = the rest
```

- **Rounding.** Tax and fee are rounded half-up to the cent; the merchant gets the exact remainder, so the entry always balances.
- **Snapshot.** The fee rate and the refund policy are stored on the payment, so changing the config later does not touch past sales.

### Refund

A refund is the mirror entry for the refunded part.

- **Partial refunds.** A partial refund is split across tax, fee and merchant in proportion to what is still refundable of each. The refund that empties the payment takes exactly what is left, so a series of partial refunds never drifts by a cent. A property test checks this over 10,000 random sequences.
- **The fee.** By default **the MoR keeps its fee** (`REFUND_FEE_POLICY=MOR_KEEPS_FEE`), like most payment providers. After a full refund, the merchant's balance is **−€6.05**: the merchant owes the MoR its fee. With `MOR_RETURNS_FEE`, everything goes back to zero.

### Balances

- **Stored balance.** Each account stores its current balance. The balance changes **in the same database transaction** as the journal entry, with an atomic `UPDATE … SET balance = balance + x`. The report simply reads these balances.
- **Report as of a date.** A report "as of" a past date sums the journal entries up to that date instead.

### Correctness under load

- **Concurrency.** Concurrent payments touching the same account queue up on the database row lock, so nothing is lost and no extra locking code is needed. Refunds of one payment are serialised by locking the payment, so they can never exceed the captured amount.
- **Invariants enforced by the database itself.**
  - Every journal entry must balance (checked at commit).
  - History is append-only: no `UPDATE` or `DELETE`.
  - The parts of every payment must add up to its amount.

## Installation and configuration

Only **Docker** is needed:

```bash
docker compose up -d --build --wait                        # build and start Postgres + the service
docker compose --profile tools run --rm k6 run seed.js     # optional: demo data, ~100 merchants / 100k payments over a year (~3 min)
docker compose --profile test run --rm test                # run the test suite in a container
docker compose --profile test down                         # stop everything
perf/run-benchmarks.sh                                     # optional: the benchmark (~25 min); perf/results/<time>/summary.md
```

| URL | |
|---|---|
| http://localhost:8090/ui/ | Console |
| http://localhost:8090/docs/ | Swagger UI |
| [docs/demo.http](docs/demo.http) | The demo scenario as HTTP requests |

Ports, database credentials, the fee rate, the refund policy and the balance mode can be changed in an optional `.env`. Start from [.env.example](.env.example); without a `.env`, the defaults apply.

## Assumptions

- The capture carries the **tax-inclusive** amount the customer paid, plus the tax rate and the tax jurisdiction.
- The MoR fee is a flat **% of the gross** amount (5% by default).
- The MoR **keeps its fee on refunds** by default; this is configurable.
- A merchant's balance **may go negative**. Recovering it belongs to payouts.
- **Multi-currency without FX**: balances are kept and reported per currency.
- Out of scope: payouts, settlement, tax remittance, chargebacks, invoicing, authentication.

Every decision with its reasoning: [docs/design.md](docs/design.md#assumptions).

## Key trade-offs

- **Own ledger on Postgres instead of TigerBeetle or Formance.** The payment and its journal entry commit in one transaction. An external ledger would turn every payment into a write to two systems that can disagree. We integrated TigerBeetle and measured it: under the same 2 CPU / 3 GB budget it was the slowest variant, so it was removed. The ledger is a small module that knows nothing about payments, so it can still be replaced.
- **Balances stored and updated synchronously, not recomputed on every read or fed from a queue.**
  - Reads are instant and always exact.
  - The cost is a short lock on the shared accounts (`fee_revenue`, `psp_clearing`) per payment. On 2 CPUs it caps `STORED` at ~600–800 captures/s, the lowest when every payment goes to one merchant (a benchmark scenario).
  - For more throughput: `BALANCE_MODE=BATCHED` turns on group commit. Queued requests share one transaction, one commit and one balance update per account; balances stay exact, and it held ~1000 rps.
- **Event ids as idempotency keys**, matching how payment-provider webhooks are retried.
- **Explicit SQL with a small JDBC helper.** `*Table` objects hold queries; `${param(value)}` interpolation binds values safely. Services coordinate transactions and business rules without an ORM.

More, including concurrency alternatives, time handling, and what each out-of-scope feature would add: [docs/design.md](docs/design.md#trade-offs).

## More

- [docs/requirements.md](docs/requirements.md): what we set out to build.
- [docs/design.md](docs/design.md): all decisions and trade-offs, tests, CI, configuration, code layout.
- [docs/domain/](docs/domain/): background on the MoR model and EU VAT.
- [AGENTS.md](AGENTS.md): working notes for contributors and AI agents.
