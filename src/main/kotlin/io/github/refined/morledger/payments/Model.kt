package io.github.refined.morledger.payments

import io.github.refined.morledger.money.Allocation
import io.github.refined.morledger.money.RefundFeePolicy
import io.github.refined.morledger.money.Split
import java.math.BigDecimal
import java.time.Instant

data class PricingSettings(
    val feeRate: BigDecimal = BigDecimal("0.05"),
    val refundFeePolicy: RefundFeePolicy = RefundFeePolicy.MOR_KEEPS_FEE,
)

data class CaptureCommand(
    val paymentId: String,
    val merchantId: String,
    val amount: Long,
    val currency: String,
    val taxRate: BigDecimal,
    val taxJurisdiction: String,
    val capturedAt: Instant?,
)

data class RefundCommand(
    val paymentId: String,
    val refundId: String,
    val amount: Long?,
    val refundedAt: Instant?,
)

data class Payment(
    val paymentId: String,
    val merchantId: String,
    val currency: String,
    val split: Split,
    val taxRate: BigDecimal,
    val taxJurisdiction: String,
    val feeRate: BigDecimal,
    val refundFeePolicy: RefundFeePolicy,
    val capturedAt: Instant,
    val recordedAt: Instant,
) {
    val amount: Long get() = split.total
}

data class Refund(
    val refundId: String,
    val paymentId: String,
    val split: Split,
    val refundedAt: Instant,
    val recordedAt: Instant,
)

enum class PaymentStatus { CAPTURED, PARTIALLY_REFUNDED, REFUNDED }

data class PaymentDetails(val payment: Payment, val refunds: List<Refund>) {
    val refunded: Split = refunds.fold(Split.ZERO) { acc, r -> acc + r.split }
    val refundable: Split = Allocation.refundable(payment.split, payment.refundFeePolicy) - refunded
    val status: PaymentStatus = when {
        refunded.total == 0L -> PaymentStatus.CAPTURED
        refunded.total < payment.amount -> PaymentStatus.PARTIALLY_REFUNDED
        else -> PaymentStatus.REFUNDED
    }
}

data class RefundResult(val refund: Refund, val payment: PaymentDetails)

data class Recorded<T>(val value: T, val created: Boolean)

sealed class DomainException(val code: String, message: String) : RuntimeException(message)

class ValidationException(message: String) : DomainException("invalid_request", message)

class NotFoundException(code: String, message: String) : DomainException(code, message)

class ConflictException(code: String, message: String) : DomainException(code, message)

class UnprocessableException(code: String, message: String) : DomainException(code, message)

data class PaymentPage(val total: Long, val rows: List<PaymentDetails>)

internal data class Stored<T>(val value: T, val fingerprint: String)
