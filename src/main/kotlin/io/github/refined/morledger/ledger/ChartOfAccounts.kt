package io.github.refined.morledger.ledger

import io.github.refined.morledger.money.Split
import java.util.zip.CRC32

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

    fun shardOf(paymentId: String, shards: Int): String {
        if (shards <= 1) return ""
        val crc = CRC32().apply { update(paymentId.toByteArray()) }.value
        return SHARD_SEPARATOR + (crc % shards).toString()
    }

    fun baseCode(code: String): String = code.substringBefore(SHARD_SEPARATOR)

    fun capture(currency: String, jurisdiction: String, merchantId: String, split: Split, shard: String = ""): List<Posting> =
        listOf(
            Posting(pspClearing(currency, shard), split.total),
            Posting(taxPayable(jurisdiction, currency, shard), -split.tax),
            Posting(feeRevenue(currency, shard), -split.fee),
            Posting(merchantPayable(merchantId, currency), -split.merchant),
        )

    fun refund(currency: String, jurisdiction: String, merchantId: String, split: Split, shard: String = ""): List<Posting> =
        capture(currency, jurisdiction, merchantId, split, shard).map { it.copy(amount = -it.amount) }
}
