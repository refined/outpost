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

The three operations the service exists for. Money is in integer **minor units** (cents for EUR); rates are decimal strings.

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

`paymentId` and `refundId` are the external event ids, so a retried event is safe: the same request reuses the recorded operation (`200`), and the same id with different data is rejected (`409`). Payment details in a replay reflect its current state, including later refunds. Errors look like `{"error": "<code>", "message": "..."}`. The read-only helper endpoints used by the console are listed in Swagger.

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

- **Rounding.** Tax and fee are rounded half-up to the currency's minor unit; the merchant gets the exact remainder, so the entry always balances.
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
- **Entry validation in code.** `validatedPostings`, called by `LedgerWriter` in both balance modes, requires every journal entry to balance in one currency. There is no database constraint checking each entry's balance at commit; this relies on using the application's write path.
- **Database constraints.** History is append-only (`UPDATE` and `DELETE` are rejected), account identity cannot change, and the parts of each payment and refund must add up to its amount.
- **Reconciliation.** `/v1/ledger/verify` compares each stored account balance with the sum of its postings. This detects balance drift; it is not an independent check that every journal entry balances.

## Installation and configuration

Only **Docker** is needed. For the walkthrough, create a `.env` in the repository root (or edit your existing one) with:

```dotenv
BALANCE_MODE=BATCHED
BATCH_MAX_SIZE=100
BATCH_MAX_WAIT_MS=0
```

`BATCHED` groups up to 100 queued operations into one database transaction, with a savepoint per operation and one summed balance update per account. Each request receives its result after commit. A zero wait takes the requests already queued without waiting to fill a batch. The measurements and the path from individual commits to group commit are in [docs/performance.md](docs/performance.md).

Then run:

```bash
docker compose up -d --build --wait                        # build and start Postgres + the service
docker compose --profile tools run --rm k6 run seed.js     # optional: demo data, ~100 merchants / 100k payments over a year (~3 min)
docker compose --profile test run --rm test                # run the test suite in a container
docker compose --profile test down                         # stop everything
```

| URL | |
|---|---|
| http://localhost:8090/ui/ | Console |
| http://localhost:8090/docs/ | Swagger UI |
| [docs/demo.http](docs/demo.http) | The demo scenario as HTTP requests |

Ports, database credentials, the fee rate, the refund policy and the balance mode can also be configured in `.env`; see [.env.example](.env.example). Without an override, the service uses `STORED`: one transaction per request. Both modes keep balances in the same transaction as their postings.

The optional benchmark is described in [docs/performance.md](docs/performance.md). Run it separately from the walkthrough: its script resets the database volume for each variant.

## Where to start in the code

Follow one capture, then one refund:

1. [PaymentService.kt](src/main/kotlin/io/github/refined/morledger/payments/PaymentService.kt): the business flow, idempotency, refund locking and transaction selection. HTTP handlers are in [PaymentRoutes.kt](src/main/kotlin/io/github/refined/morledger/api/PaymentRoutes.kt) and [ReconciliationRoutes.kt](src/main/kotlin/io/github/refined/morledger/api/ReconciliationRoutes.kt).
2. [Allocation.kt](src/main/kotlin/io/github/refined/morledger/money/Allocation.kt): money arithmetic, rounding and partial refunds. [ChartOfAccounts.kt](src/main/kotlin/io/github/refined/morledger/ledger/ChartOfAccounts.kt) turns those amounts into postings.
3. [LedgerWriter.kt](src/main/kotlin/io/github/refined/morledger/ledger/LedgerWriter.kt): entry validation, account lookup and journal writes. [LedgerBatchCommit.kt](src/main/kotlin/io/github/refined/morledger/ledger/LedgerBatchCommit.kt) adds the queue, savepoints and group commit.
4. [LedgerTable.kt](src/main/kotlin/io/github/refined/morledger/ledger/LedgerTable.kt): ledger SQL and mapping; `PaymentsTable` and `RefundsTable` do the same for business records. [BalanceReport.kt](src/main/kotlin/io/github/refined/morledger/reports/BalanceReport.kt) assembles the required report.
5. [Integration tests](src/integrationTest/kotlin/io/github/refined/morledger): the HTTP scenarios against real Postgres, including retries, concurrent refunds, rollback and equivalent results in both modes. [Unit tests](src/test/kotlin/io/github/refined/morledger) cover the pure calculations and SQL builder.

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
  - Current reports read account balances instead of summing the full posting history, and reflect committed transactions.
  - The cost is a short lock on the shared accounts (`fee_revenue`, `psp_clearing`) per payment. On 2 CPUs it caps `STORED` at ~600–900 captures/s, the lowest when every payment goes to one merchant (a benchmark scenario).
  - For more throughput: `BALANCE_MODE=BATCHED` turns on group commit. Queued requests share one transaction, one commit and one balance update per account; balances stay exact, and it holds ~1100 rps.
- **Event ids as idempotency keys**, matching how payment-provider webhooks are retried.
- **Explicit SQL with a small JDBC helper.** `*Table` objects hold queries; `${param(value)}` interpolation binds values safely. Services coordinate transactions and business rules without an ORM.

More, including concurrency alternatives, time handling, and what each out-of-scope feature would add: [docs/design.md](docs/design.md#trade-offs).

## More

- [docs/requirements.md](docs/requirements.md): what we set out to build.
- [docs/design.md](docs/design.md): all decisions and trade-offs, tests, CI, configuration, code layout.
- [docs/domain/](docs/domain/): background on the MoR model and EU VAT.
- [AGENTS.md](AGENTS.md): working notes for contributors and AI agents.
