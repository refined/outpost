# Requirements: Mini MoR Ledger Service

## Goal

A small ledger service in a new domain, the Merchant of Record. The scope is deliberately small. What matters is how the domain is approached: the reasoning and trade-offs count as much as the implementation. Attention to detail and willingness to dig into the domain are the point. LLMs, docs and libraries are fair game.

## Context

A **Merchant of Record (MoR)** is the seller of record. It:
- collects payment from the customer;
- is liable for remitting sales tax/VAT to the relevant tax authority;
- takes its own fee;
- owes the remainder to the merchant.

## What we build

A service exposing APIs to:

1. **Capture a payment.** Record that a customer payment was captured for a given merchant.
2. **Refund a payment.** Record a refund against a previously captured payment.
3. **Balances report.** It answers two questions:
   - How much does the MoR owe tax authorities?
   - What are the merchants' balances with the MoR?

## Simplifications we accept

- A single flat MoR fee (e.g. a fixed %) is fine.
- The tax rate can be communicated on the capture event/webhook.
- Single currency is fine; **multi-currency is a nice-to-have**.
- A full refund is enough for the main flow; **partial refunds are a nice-to-have**.
- Where the spec is ambiguous, we make a reasonable decision and **document it**. How we reason about the gaps matters.

## Ledger

Either use something off-the-shelf (Formance, TigerBeetle) or roll our own. Whichever we pick, the choice and its trade-offs must be explained.

## Technical requirements

- Kotlin.
- Instructions to run it locally.

## Deliverables

- A GitHub repo.
- A README covering:
  - how to run it;
  - the APIs;
  - assumptions;
  - key trade-offs.

## Timeline

Ready by **2026-10-19**.
