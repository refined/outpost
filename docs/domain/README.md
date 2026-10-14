# Domain background

Notes from an earlier Merchant of Record (MoR) system design exercise: an EU MoR platform for a US SaaS merchant. They are not part of the service. They are here as context for the ledger's chart of accounts and its assumptions.

## Summary of that design

A platform that lets merchants sell **across borders** while **a Dutch legal entity is the seller of record**. The EU was the first market designed in depth.

The MoR does the following:
- takes the payment through a payment provider;
- collects and remits VAT via the EU One-Stop Shop;
- issues invoices;
- carries refunds and chargebacks;
- pays merchants out through a payout provider.

Three principles:

1. **The MoR is the legal seller**, so it owns tax, invoices and consent evidence.
2. **Money is never lost or doubled.** This rests on a double-entry ledger, idempotency and reconciliation.
3. **Tax and invoices are auditable.** Markets beyond the EU are configuration.

## Notes

- [mor-context.md](mor-context.md): what MoR means compared with a PSP or PayFac, the legal and money chain, EU VAT for digital services, invoicing, consumer law, sizing and a glossary.
- [design-options.md](design-options.md): design forks and trade-offs. D5 ("Money movement, ledger and payouts") has a worked example of ledger postings for a capture, settlement, payout and refund.
