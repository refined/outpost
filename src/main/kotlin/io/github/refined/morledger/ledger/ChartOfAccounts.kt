package io.github.refined.morledger.ledger

import io.github.refined.morledger.money.Split
import java.util.zip.CRC32

/**
 * The MoR's chart of accounts (one set per currency) and the postings each business event produces.
 *
 * | Account                         | Kind      | Normal |
 * |---------------------------------|-----------|--------|
 * | `psp_clearing`                  | asset     | debit  |
 * | `tax_payable:{jurisdiction}`    | liability | credit |
 * | `merchant_payable:{merchantId}` | liability | credit |
 * | `fee_revenue`                   | revenue   | credit |
 *
 * **Hot-account sharding** (optional, `HOT_ACCOUNT_SHARDS`): every payment touches `psp_clearing`, `fee_revenue` and
 * its `tax_payable`, so with stored balances those rows serialise all payments. With N shards they become
 * `psp_clearing#0..N-1` etc.; the shard is a stable hash of the payment id, so a refund lands on its capture's shard.
 * Reports fold shards back with [baseCode]. Merchant accounts are not sharded.
 */
object ChartOfAccounts {
    const val PSP_CLEARING = "psp_clearing"
    const val FEE_REVENUE = "fee_revenue"
    const val TAX_PAYABLE_PREFIX = "tax_payable:"
    const val MERCHANT_PAYABLE_PREFIX = "merchant_payable:"
    private const val SHARD_SEPARATOR = '#' // ids and jurisdictions cannot contain '#'

    fun pspClearing(currency: String, shard: String = "") = Account(PSP_CLEARING + shard, currency, NormalSide.DEBIT)

    fun feeRevenue(currency: String, shard: String = "") = Account(FEE_REVENUE + shard, currency, NormalSide.CREDIT)

    fun taxPayable(jurisdiction: String, currency: String, shard: String = "") =
        Account(TAX_PAYABLE_PREFIX + jurisdiction + shard, currency, NormalSide.CREDIT)

    fun merchantPayable(merchantId: String, currency: String) =
        Account(MERCHANT_PAYABLE_PREFIX + merchantId, currency, NormalSide.CREDIT)

    /** The shard suffix for a payment: empty with one shard, `#k` otherwise. */
    fun shardOf(paymentId: String, shards: Int): String {
        if (shards <= 1) return ""
        val crc = CRC32().apply { update(paymentId.toByteArray()) }.value
        return SHARD_SEPARATOR + (crc % shards).toString()
    }

    /** The account code without its shard suffix. */
    fun baseCode(code: String): String = code.substringBefore(SHARD_SEPARATOR)

    /** Capture: Dr clearing (gross) / Cr tax payable, Cr fee revenue, Cr merchant payable. */
    fun capture(currency: String, jurisdiction: String, merchantId: String, split: Split, shard: String = ""): List<Posting> =
        listOf(
            Posting(pspClearing(currency, shard), split.total),
            Posting(taxPayable(jurisdiction, currency, shard), -split.tax),
            Posting(feeRevenue(currency, shard), -split.fee),
            Posting(merchantPayable(merchantId, currency), -split.merchant),
        )

    /** Refund: the mirror image of a capture for the refunded parts. */
    fun refund(currency: String, jurisdiction: String, merchantId: String, split: Split, shard: String = ""): List<Posting> =
        capture(currency, jurisdiction, merchantId, split, shard).map { it.copy(amount = -it.amount) }
}
