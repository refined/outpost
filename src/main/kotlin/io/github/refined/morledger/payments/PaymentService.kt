package io.github.refined.morledger.payments

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.ChartOfAccounts
import io.github.refined.morledger.ledger.EntryKind
import io.github.refined.morledger.ledger.Ledger
import io.github.refined.morledger.ledger.NewJournalEntry
import io.github.refined.morledger.money.Allocation
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency
import javax.sql.DataSource

/**
 * Records captures and refunds. Each call is one database transaction: the business record and its journal entry
 * commit together or not at all.
 *
 * Idempotency: `paymentId` / `refundId` are the external event ids. Re-sending the same request returns the stored
 * result (`created = false`); re-using an id with a different payload is an [ConflictException].
 */
class PaymentService(
    private val dataSource: DataSource,
    private val pricing: PricingSettings = PricingSettings(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun capture(cmd: CaptureCommand): Recorded<PaymentDetails> {
        validateId("paymentId", cmd.paymentId)
        validateId("merchantId", cmd.merchantId)
        val currency = validateCurrency(cmd.currency)
        val jurisdiction = validateJurisdiction(cmd.taxJurisdiction)
        if (cmd.amount <= 0) throw ValidationException("amount must be a positive number of minor units")
        val taxRate = validateRate("taxRate", cmd.taxRate)
        val capturedAt = validateEventTime("capturedAt", cmd.capturedAt)

        val fingerprint = fingerprint(
            "capture", cmd.paymentId, cmd.merchantId, cmd.amount, currency, taxRate.toPlainString(), jurisdiction,
            cmd.capturedAt?.let { capturedAt },
        )
        val split = try {
            Allocation.splitCapture(cmd.amount, taxRate, pricing.feeRate)
        } catch (e: IllegalArgumentException) {
            throw UnprocessableException("amount_too_small", e.message ?: "amount cannot cover tax and fee")
        }
        val payment = Payment(
            paymentId = cmd.paymentId,
            merchantId = cmd.merchantId,
            currency = currency,
            split = split,
            taxRate = taxRate,
            taxJurisdiction = jurisdiction,
            feeRate = pricing.feeRate,
            refundFeePolicy = pricing.refundFeePolicy,
            capturedAt = capturedAt,
            recordedAt = capturedAt, // placeholder; the stored value comes from the database
        )

        return dataSource.transaction { conn ->
            val created = PaymentRepository.insertPayment(conn, payment, fingerprint)
            if (created) {
                Ledger.post(
                    conn,
                    NewJournalEntry(
                        kind = EntryKind.CAPTURE,
                        paymentId = payment.paymentId,
                        refundId = null,
                        effectiveAt = capturedAt,
                        postings = ChartOfAccounts.capture(currency, jurisdiction, payment.merchantId, split),
                    ),
                )
            } else {
                val existing = PaymentRepository.findPayment(conn, cmd.paymentId)!!
                if (existing.fingerprint != fingerprint) {
                    throw ConflictException(
                        "idempotency_conflict",
                        "payment ${cmd.paymentId} was already captured with a different payload",
                    )
                }
            }
            Recorded(PaymentRepository.details(conn, cmd.paymentId)!!, created)
        }
    }

    fun refund(cmd: RefundCommand): Recorded<RefundResult> {
        validateId("paymentId", cmd.paymentId)
        validateId("refundId", cmd.refundId)
        if (cmd.amount != null && cmd.amount <= 0) throw ValidationException("amount must be a positive number of minor units")
        val refundedAt = validateEventTime("refundedAt", cmd.refundedAt)
        val fingerprint = fingerprint("refund", cmd.paymentId, cmd.refundId, cmd.amount, cmd.refundedAt?.let { refundedAt })

        return dataSource.transaction { conn ->
            // Lock first: concurrent refunds of the same payment queue up here and see each other's results.
            val payment = PaymentRepository.findPayment(conn, cmd.paymentId, forUpdate = true)?.value
                ?: throw NotFoundException("payment_not_found", "payment ${cmd.paymentId} has not been captured")

            PaymentRepository.findRefund(conn, cmd.refundId)?.let { existing ->
                return@transaction replayRefund(conn, existing, fingerprint)
            }

            if (refundedAt.isBefore(payment.capturedAt)) {
                throw UnprocessableException("refund_before_capture", "refundedAt is before the payment's capturedAt")
            }
            val before = PaymentDetails(payment, PaymentRepository.refundsOf(conn, payment.paymentId))
            val remaining = before.refundable
            if (remaining.total == 0L) {
                throw UnprocessableException("payment_fully_refunded", "payment ${payment.paymentId} is already fully refunded")
            }
            val amount = cmd.amount ?: remaining.total
            if (amount > remaining.total) {
                throw UnprocessableException(
                    "refund_exceeds_refundable",
                    "refund of $amount exceeds the refundable ${remaining.total} for payment ${payment.paymentId}",
                )
            }

            val refund = Refund(cmd.refundId, payment.paymentId, Allocation.allocateRefund(remaining, amount), refundedAt, refundedAt)
            if (!PaymentRepository.insertRefund(conn, refund, fingerprint)) {
                // The same refundId was just recorded for another payment (that payment's lock didn't stop us).
                throw ConflictException("idempotency_conflict", "refund ${cmd.refundId} already exists")
            }
            Ledger.post(
                conn,
                NewJournalEntry(
                    kind = EntryKind.REFUND,
                    paymentId = payment.paymentId,
                    refundId = refund.refundId,
                    effectiveAt = refundedAt,
                    postings = ChartOfAccounts.refund(payment.currency, payment.taxJurisdiction, payment.merchantId, refund.split),
                ),
            )
            val after = PaymentRepository.details(conn, payment.paymentId)!!
            Recorded(RefundResult(after.refunds.single { it.refundId == refund.refundId }, after), created = true)
        }
    }

    fun get(paymentId: String): PaymentDetails =
        dataSource.transaction { PaymentRepository.details(it, paymentId) }
            ?: throw NotFoundException("payment_not_found", "payment $paymentId not found")

    private fun replayRefund(conn: java.sql.Connection, existing: Stored<Refund>, fingerprint: String): Recorded<RefundResult> {
        if (existing.fingerprint != fingerprint) {
            throw ConflictException("idempotency_conflict", "refund ${existing.value.refundId} was already recorded with a different payload")
        }
        return Recorded(RefundResult(existing.value, PaymentRepository.details(conn, existing.value.paymentId)!!), created = false)
    }

    private fun validateEventTime(field: String, value: Instant?): Instant {
        val now = clock.instant()
        val at = (value ?: now).truncatedTo(ChronoUnit.MICROS) // Postgres keeps microseconds
        if (at.isAfter(now.plus(MAX_CLOCK_SKEW))) throw ValidationException("$field is in the future")
        return at
    }

    companion object {
        private val MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
        private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
        private val JURISDICTION = Regex("^[A-Z]{2}(-[A-Z0-9]{1,3})?$")

        fun validateId(field: String, value: String) {
            if (!ID.matches(value)) {
                throw ValidationException("$field must be 1-128 characters of letters, digits, '_', '.', ':' or '-'")
            }
        }

        fun validateCurrency(value: String): String {
            val code = value.uppercase()
            val known = Regex("^[A-Z]{3}$").matches(code) && runCatching { Currency.getInstance(code) }.isSuccess
            if (!known) throw ValidationException("currency must be an ISO 4217 code, e.g. EUR")
            return code
        }

        fun validateJurisdiction(value: String): String {
            if (!JURISDICTION.matches(value)) {
                throw ValidationException("taxJurisdiction must be an ISO 3166 code, e.g. NL or US-CA")
            }
            return value
        }

        fun validateRate(field: String, value: BigDecimal): BigDecimal {
            val rate = value.stripTrailingZeros()
            if (rate.signum() < 0 || rate >= BigDecimal.ONE) throw ValidationException("$field must be a fraction in [0, 1), e.g. \"0.21\"")
            if (rate.scale() > 6) throw ValidationException("$field supports at most 6 decimal places")
            return if (rate.scale() < 0) rate.setScale(0) else rate
        }

        private fun fingerprint(vararg parts: Any?): String {
            val canonical = parts.joinToString("|") { it?.toString() ?: "" }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
