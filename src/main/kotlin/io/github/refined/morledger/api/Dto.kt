package io.github.refined.morledger.api

import io.github.refined.morledger.ledger.JournalEntry
import io.github.refined.morledger.money.Split
import io.github.refined.morledger.payments.PaymentDetails
import io.github.refined.morledger.payments.Refund
import io.github.refined.morledger.payments.RefundResult
import io.github.refined.morledger.reports.Balances
import kotlinx.serialization.Serializable


@Serializable
data class CaptureRequest(
    val paymentId: String,
    val merchantId: String,
    val amount: Long,
    val currency: String,
    val taxRate: String,
    val taxJurisdiction: String,
    val capturedAt: String? = null,
)

@Serializable
data class RefundRequest(
    val refundId: String,
    val amount: Long? = null,
    val refundedAt: String? = null,
)

@Serializable
data class PartsDto(val amount: Long, val taxAmount: Long, val feeAmount: Long, val merchantAmount: Long)

@Serializable
data class RefundDto(
    val refundId: String,
    val paymentId: String,
    val amount: Long,
    val taxAmount: Long,
    val feeAmount: Long,
    val merchantAmount: Long,
    val refundedAt: String,
    val recordedAt: String,
)

@Serializable
data class PaymentDto(
    val paymentId: String,
    val merchantId: String,
    val currency: String,
    val status: String,
    val amount: Long,
    val taxAmount: Long,
    val feeAmount: Long,
    val merchantAmount: Long,
    val taxRate: String,
    val taxJurisdiction: String,
    val feeRate: String,
    val refundFeePolicy: String,
    val capturedAt: String,
    val recordedAt: String,
    val refunded: PartsDto,
    val refundable: PartsDto,
    val refunds: List<RefundDto>,
)

@Serializable
data class RefundResponse(val refund: RefundDto, val payment: PaymentDto)

@Serializable
data class PaymentsResponse(val payments: List<PaymentDto>, val total: Long)

@Serializable
data class TaxOwedDto(val jurisdiction: String, val currency: String, val amountOwed: Long)

@Serializable
data class MerchantBalanceDto(val merchantId: String, val currency: String, val balance: Long)

@Serializable
data class MorPositionDto(val currency: String, val feeRevenue: Long, val pspClearing: Long)

@Serializable
data class IntegrityDto(val balanced: Boolean, val unbalancedCurrencies: List<String>)

@Serializable
data class BalancesResponse(
    val asOf: String?,
    val taxAuthorities: List<TaxOwedDto>,
    val merchants: List<MerchantBalanceDto>,
    val mor: List<MorPositionDto>,
    val integrity: IntegrityDto,
)

@Serializable
data class PostingDto(val account: String, val currency: String, val debit: Long?, val credit: Long?)

@Serializable
data class JournalEntryDto(
    val id: Long,
    val kind: String,
    val paymentId: String,
    val refundId: String?,
    val effectiveAt: String,
    val recordedAt: String,
    val postings: List<PostingDto>,
)

@Serializable
data class JournalResponse(val entries: List<JournalEntryDto>)

fun Split.toParts() = PartsDto(total, tax, fee, merchant)

fun Refund.toDto() = RefundDto(
    refundId = refundId,
    paymentId = paymentId,
    amount = split.total,
    taxAmount = split.tax,
    feeAmount = split.fee,
    merchantAmount = split.merchant,
    refundedAt = refundedAt.toString(),
    recordedAt = recordedAt.toString(),
)

fun PaymentDetails.toDto() = PaymentDto(
    paymentId = payment.paymentId,
    merchantId = payment.merchantId,
    currency = payment.currency,
    status = status.name,
    amount = payment.amount,
    taxAmount = payment.split.tax,
    feeAmount = payment.split.fee,
    merchantAmount = payment.split.merchant,
    taxRate = payment.taxRate.toPlainString(),
    taxJurisdiction = payment.taxJurisdiction,
    feeRate = payment.feeRate.toPlainString(),
    refundFeePolicy = payment.refundFeePolicy.name,
    capturedAt = payment.capturedAt.toString(),
    recordedAt = payment.recordedAt.toString(),
    refunded = refunded.toParts(),
    refundable = refundable.toParts(),
    refunds = refunds.map { it.toDto() },
)

fun RefundResult.toResponse() = RefundResponse(refund.toDto(), payment.toDto())

fun Balances.toResponse() = BalancesResponse(
    asOf = asOf?.toString(),
    taxAuthorities = taxAuthorities.map { TaxOwedDto(it.jurisdiction, it.currency, it.amountOwed) },
    merchants = merchants.map { MerchantBalanceDto(it.merchantId, it.currency, it.balance) },
    mor = mor.map { MorPositionDto(it.currency, it.feeRevenue, it.pspClearing) },
    integrity = IntegrityDto(integrity.balanced, integrity.unbalancedCurrencies),
)

fun JournalEntry.toDto() = JournalEntryDto(
    id = id,
    kind = kind.name,
    paymentId = paymentId,
    refundId = refundId,
    effectiveAt = effectiveAt.toString(),
    recordedAt = recordedAt.toString(),
    postings = postings.map {
        PostingDto(
            account = it.account.code,
            currency = it.account.currency,
            debit = it.amount.takeIf { a -> a > 0 },
            credit = (-it.amount).takeIf { a -> a > 0 },
        )
    },
)
