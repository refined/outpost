package io.github.refined.morledger.payments

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.BatchGroup
import io.github.refined.morledger.ledger.BatchExecution
import io.github.refined.morledger.ledger.ChartOfAccounts
import io.github.refined.morledger.ledger.EntryKind
import io.github.refined.morledger.ledger.LedgerBatchCommit
import io.github.refined.morledger.ledger.LedgerWriter
import io.github.refined.morledger.ledger.NewJournalEntry
import io.github.refined.morledger.money.Allocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val ledger: LedgerWriter = LedgerWriter(),
    private val batchCommit: LedgerBatchCommit? = null,
) {
    suspend fun capture(cmd: CaptureCommand): Recorded<PaymentDetails> {
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
            feeRate = pricing.feeRate.stripTrailingZeros(), // as it reads back from the database
            refundFeePolicy = pricing.refundFeePolicy,
            capturedAt = capturedAt,
            recordedAt = capturedAt, // placeholder; the stored value comes from the database
        )
        val capture = Stored(payment, fingerprint)

        return batchCommit?.submit(captures, capture, BatchExecution.TOGETHER)
            ?: withContext(Dispatchers.IO) { dataSource.transaction { recordCaptures(it, listOf(capture), null) } }.single().getOrThrow()
    }

    private val captures = BatchGroup<Stored<Payment>, Recorded<PaymentDetails>> { conn, items, deltas -> recordCaptures(conn, items, deltas) }

    private fun recordCaptures(
        conn: Connection,
        items: List<Stored<Payment>>,
        deltas: MutableMap<Long, Long>?,
    ): List<Result<Recorded<PaymentDetails>>> {
        val firsts = items.indices.distinctBy { items[it].value.paymentId }
        val recordedAt = PaymentsTable.insertAll(conn, firsts.map { items[it] })
        val created = firsts.filter { items[it].value.paymentId in recordedAt }.toSet()
        ledger.postAll(conn, created.map { captureEntry(items[it].value) }, deltas)
        return items.mapIndexed { i, capture ->
            if (i in created) {
                val payment = capture.value.copy(recordedAt = recordedAt.getValue(capture.value.paymentId))
                Result.success(Recorded(PaymentDetails(payment, emptyList()), created = true))
            } else {
                runCatching { replayCapture(conn, capture) }
            }
        }
    }

    private fun captureEntry(payment: Payment) = NewJournalEntry(
        kind = EntryKind.CAPTURE,
        paymentId = payment.paymentId,
        refundId = null,
        effectiveAt = payment.capturedAt,
        postings = ChartOfAccounts.capture(payment.currency, payment.taxJurisdiction, payment.merchantId, payment.split),
    )

    private fun replayCapture(conn: Connection, capture: Stored<Payment>): Recorded<PaymentDetails> {
        val paymentId = capture.value.paymentId
        if (PaymentsTable.find(conn, paymentId)!!.fingerprint != capture.fingerprint) {
            throw ConflictException("idempotency_conflict", "payment $paymentId was already captured with a different payload")
        }
        return Recorded(details(conn, paymentId)!!, created = false)
    }

    suspend fun refund(cmd: RefundCommand): Recorded<RefundResult> {
        validateId("paymentId", cmd.paymentId)
        validateId("refundId", cmd.refundId)
        if (cmd.amount != null && cmd.amount <= 0) throw ValidationException("amount must be a positive number of minor units")
        val refundedAt = validateEventTime("refundedAt", cmd.refundedAt)
        val fingerprint = fingerprint("refund", cmd.paymentId, cmd.refundId, cmd.amount, cmd.refundedAt?.let { refundedAt })

        return write { conn, deltas ->
            // Lock first: concurrent refunds of the same payment queue up here and see each other's results.
            val payment = PaymentsTable.find(conn, cmd.paymentId, forUpdate = true)?.value
                ?: throw NotFoundException("payment_not_found", "payment ${cmd.paymentId} has not been captured")

            RefundsTable.find(conn, cmd.refundId)?.let { existing ->
                return@write replayRefund(conn, existing, fingerprint)
            }

            if (refundedAt.isBefore(payment.capturedAt)) {
                throw UnprocessableException("refund_before_capture", "refundedAt is before the payment's capturedAt")
            }
            val before = PaymentDetails(payment, RefundsTable.forPayment(conn, payment.paymentId))
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
            if (!RefundsTable.insert(conn, refund, fingerprint)) {
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
            val after = details(conn, payment.paymentId)!!
            Recorded(RefundResult(after.refunds.single { it.refundId == refund.refundId }, after), created = true)
        }
    }

    fun page(merchantId: String?, status: PaymentStatus?, limit: Int, offset: Int): PaymentPage =
        dataSource.transaction { conn ->
            val page = PaymentsTable.page(conn, merchantId, status, limit, offset)
            PaymentPage(page.total, page.rows.map { PaymentDetails(it, RefundsTable.forPayment(conn, it.paymentId)) })
        }

    fun get(paymentId: String): PaymentDetails =
        dataSource.transaction { details(it, paymentId) }
            ?: throw NotFoundException("payment_not_found", "payment $paymentId not found")

    private suspend fun <T> write(block: (Connection, MutableMap<Long, Long>?) -> T): T =
        if (batchCommit != null) batchCommit.submit(block) else withContext(Dispatchers.IO) { dataSource.transaction { block(it, null) } }

    private fun details(conn: Connection, paymentId: String): PaymentDetails? =
        PaymentsTable.find(conn, paymentId)?.let { PaymentDetails(it.value, RefundsTable.forPayment(conn, paymentId)) }

    private fun replayRefund(conn: Connection, existing: Stored<Refund>, fingerprint: String): Recorded<RefundResult> {
        if (existing.fingerprint != fingerprint) {
            throw ConflictException("idempotency_conflict", "refund ${existing.value.refundId} was already recorded with a different payload")
        }
        return Recorded(RefundResult(existing.value, details(conn, existing.value.paymentId)!!), created = false)
    }

    private fun validateEventTime(field: String, value: Instant?): Instant {
        val now = clock.instant()
        val at = (value ?: now).truncatedTo(ChronoUnit.MICROS) // Postgres keeps microseconds
        if (at.isAfter(now.plus(MAX_CLOCK_SKEW))) throw ValidationException("$field is in the future")
        return at
    }
}

private val MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
