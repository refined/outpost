package io.github.refined.morledger.payments

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.ChartOfAccounts
import io.github.refined.morledger.ledger.EntryKind
import io.github.refined.morledger.ledger.GroupCommitter
import io.github.refined.morledger.ledger.Ledger
import io.github.refined.morledger.ledger.NewJournalEntry
import io.github.refined.morledger.money.Allocation
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

class PaymentService(
    private val dataSource: DataSource,
    private val pricing: PricingSettings = PricingSettings(),
    private val clock: Clock = Clock.systemUTC(),
    private val ledger: Ledger = Ledger(),
    private val committer: GroupCommitter? = null,
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

        return write { conn, deltas ->
            val created = PaymentRepository.insertPayment(conn, payment, fingerprint)
            if (created) {
                ledger.post(
                    conn,
                    NewJournalEntry(
                        kind = EntryKind.CAPTURE,
                        paymentId = payment.paymentId,
                        refundId = null,
                        effectiveAt = capturedAt,
                        postings = ChartOfAccounts.capture(currency, jurisdiction, payment.merchantId, split),
                    ),
                    deltas,
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

        return write { conn, deltas ->
            // Lock first: concurrent refunds of the same payment queue up here and see each other's results.
            val payment = PaymentRepository.findPayment(conn, cmd.paymentId, forUpdate = true)?.value
                ?: throw NotFoundException("payment_not_found", "payment ${cmd.paymentId} has not been captured")

            PaymentRepository.findRefund(conn, cmd.refundId)?.let { existing ->
                return@write replayRefund(conn, existing, fingerprint)
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
            ledger.post(
                conn,
                NewJournalEntry(
                    kind = EntryKind.REFUND,
                    paymentId = payment.paymentId,
                    refundId = refund.refundId,
                    effectiveAt = refundedAt,
                    postings = ChartOfAccounts.refund(payment.currency, payment.taxJurisdiction, payment.merchantId, refund.split),
                ),
                deltas,
            )
            val after = PaymentRepository.details(conn, payment.paymentId)!!
            Recorded(RefundResult(after.refunds.single { it.refundId == refund.refundId }, after), created = true)
        }
    }

    fun page(merchantId: String?, status: PaymentStatus?, limit: Int, offset: Int): Pair<Long, List<PaymentDetails>> =
        dataSource.transaction { PaymentRepository.page(it, merchantId, status, limit, offset) }

    fun get(paymentId: String): PaymentDetails =
        dataSource.transaction { PaymentRepository.details(it, paymentId) }
            ?: throw NotFoundException("payment_not_found", "payment $paymentId not found")

    private fun <T> write(block: (Connection, MutableMap<Long, Long>?) -> T): T =
        if (committer != null) committer.submit(block) else dataSource.transaction { block(it, null) }

    private fun replayRefund(conn: Connection, existing: Stored<Refund>, fingerprint: String): Recorded<RefundResult> {
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
}

private val MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
