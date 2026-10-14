package io.github.refined.morledger.ledger

import io.github.refined.morledger.money.Split

/**
 * The MoR's chart of accounts (one set per currency) and the postings each business event produces.
 *
 * | Account                         | Kind      | Normal |
 * |---------------------------------|-----------|--------|
 * | `psp_clearing`                  | asset     | debit  |
 * | `tax_payable:{jurisdiction}`    | liability | credit |
 * | `merchant_payable:{merchantId}` | liability | credit |
 * | `fee_revenue`                   | revenue   | credit |
 */
object ChartOfAccounts {
    const val PSP_CLEARING = "psp_clearing"
    const val FEE_REVENUE = "fee_revenue"
    const val TAX_PAYABLE_PREFIX = "tax_payable:"
    const val MERCHANT_PAYABLE_PREFIX = "merchant_payable:"

    fun pspClearing(currency: String) = Account(PSP_CLEARING, currency, NormalSide.DEBIT)

    fun feeRevenue(currency: String) = Account(FEE_REVENUE, currency, NormalSide.CREDIT)

    fun taxPayable(jurisdiction: String, currency: String) = Account(TAX_PAYABLE_PREFIX + jurisdiction, currency, NormalSide.CREDIT)

    fun merchantPayable(merchantId: String, currency: String) =
        Account(MERCHANT_PAYABLE_PREFIX + merchantId, currency, NormalSide.CREDIT)

    /** Capture: Dr clearing (gross) / Cr tax payable, Cr fee revenue, Cr merchant payable. */
    fun capture(currency: String, jurisdiction: String, merchantId: String, split: Split): List<Posting> = listOf(
        Posting(pspClearing(currency), split.total),
        Posting(taxPayable(jurisdiction, currency), -split.tax),
        Posting(feeRevenue(currency), -split.fee),
        Posting(merchantPayable(merchantId, currency), -split.merchant),
    )

    /** Refund: the mirror image of a capture for the refunded parts. */
    fun refund(currency: String, jurisdiction: String, merchantId: String, split: Split): List<Posting> =
        capture(currency, jurisdiction, merchantId, split).map { it.copy(amount = -it.amount) }
}
