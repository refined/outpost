package io.github.refined.morledger.payments

import io.github.refined.morledger.money.RefundFeePolicy
import io.github.refined.morledger.money.Split
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A stored row together with the fingerprint of the request that created it. */
data class Stored<T>(val value: T, val fingerprint: String)

/** SQL for the `payments` and `refunds` tables. Callers own the transaction. */
object PaymentRepository {
    private const val PAYMENT_COLUMNS = """
        payment_id, merchant_id, currency, tax_amount, fee_amount, merchant_amount, tax_rate, tax_jurisdiction,
        fee_rate, refund_fee_policy, captured_at, recorded_at, fingerprint
    """

    private const val REFUND_COLUMNS = """
        refund_id, payment_id, tax_amount, fee_amount, merchant_amount, refunded_at, recorded_at, fingerprint
    """

    /** Inserts the payment unless its id exists. Returns false on a duplicate id (concurrent inserts wait for each other). */
    fun insertPayment(conn: Connection, p: Payment, fingerprint: String): Boolean =
        conn.prepareStatement(
            """
            INSERT INTO payments (payment_id, merchant_id, currency, amount, tax_amount, fee_amount, merchant_amount,
                                  tax_rate, tax_jurisdiction, fee_rate, refund_fee_policy, captured_at, fingerprint)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (payment_id) DO NOTHING
            """.trimIndent(),
        ).use { st ->
            st.setString(1, p.paymentId)
            st.setString(2, p.merchantId)
            st.setString(3, p.currency)
            st.setLong(4, p.amount)
            st.setLong(5, p.split.tax)
            st.setLong(6, p.split.fee)
            st.setLong(7, p.split.merchant)
            st.setBigDecimal(8, p.taxRate)
            st.setString(9, p.taxJurisdiction)
            st.setBigDecimal(10, p.feeRate)
            st.setString(11, p.refundFeePolicy.name)
            st.setObject(12, p.capturedAt.atOffset(ZoneOffset.UTC))
            st.setString(13, fingerprint)
            st.executeUpdate() == 1
        }

    /** [forUpdate] row-locks the payment: refunds of one payment are serialised on it. */
    fun findPayment(conn: Connection, paymentId: String, forUpdate: Boolean = false): Stored<Payment>? =
        conn.prepareStatement(
            "SELECT $PAYMENT_COLUMNS FROM payments WHERE payment_id = ?" + if (forUpdate) " FOR UPDATE" else "",
        ).use { st ->
            st.setString(1, paymentId)
            st.executeQuery().use { rs -> if (rs.next()) Stored(rs.toPayment(), rs.getString("fingerprint")) else null }
        }

    fun insertRefund(conn: Connection, r: Refund, fingerprint: String): Boolean =
        conn.prepareStatement(
            """
            INSERT INTO refunds (refund_id, payment_id, amount, tax_amount, fee_amount, merchant_amount, refunded_at, fingerprint)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (refund_id) DO NOTHING
            """.trimIndent(),
        ).use { st ->
            st.setString(1, r.refundId)
            st.setString(2, r.paymentId)
            st.setLong(3, r.split.total)
            st.setLong(4, r.split.tax)
            st.setLong(5, r.split.fee)
            st.setLong(6, r.split.merchant)
            st.setObject(7, r.refundedAt.atOffset(ZoneOffset.UTC))
            st.setString(8, fingerprint)
            st.executeUpdate() == 1
        }

    fun findRefund(conn: Connection, refundId: String): Stored<Refund>? =
        conn.prepareStatement("SELECT $REFUND_COLUMNS FROM refunds WHERE refund_id = ?").use { st ->
            st.setString(1, refundId)
            st.executeQuery().use { rs -> if (rs.next()) Stored(rs.toRefund(), rs.getString("fingerprint")) else null }
        }

    fun refundsOf(conn: Connection, paymentId: String): List<Refund> =
        conn.prepareStatement("SELECT $REFUND_COLUMNS FROM refunds WHERE payment_id = ? ORDER BY recorded_at, refund_id").use { st ->
            st.setString(1, paymentId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRefund()) } }
        }

    /**
     * A page of payments, latest capture first, optionally for one merchant and/or one status. Returns (total, page).
     * The status is derived from the refunded amount, the same way [PaymentDetails.status] does.
     */
    fun page(conn: Connection, merchantId: String?, status: PaymentStatus?, limit: Int, offset: Int): Pair<Long, List<PaymentDetails>> {
        val base = """
            SELECT p.*, CASE WHEN COALESCE(r.refunded, 0) = 0 THEN 'CAPTURED'
                             WHEN r.refunded < p.amount THEN 'PARTIALLY_REFUNDED'
                             ELSE 'REFUNDED' END AS status
            FROM payments p
            LEFT JOIN (SELECT payment_id, sum(amount) AS refunded FROM refunds GROUP BY payment_id) r ON r.payment_id = p.payment_id
            WHERE CAST(? AS TEXT) IS NULL OR p.merchant_id = CAST(? AS TEXT)
        """.trimIndent()
        val statusFilter = "WHERE CAST(? AS TEXT) IS NULL OR x.status = CAST(? AS TEXT)"
        fun java.sql.PreparedStatement.bindFilters() {
            setString(1, merchantId)
            setString(2, merchantId)
            setString(3, status?.name)
            setString(4, status?.name)
        }
        val total = conn.prepareStatement("SELECT count(*) FROM ($base) x $statusFilter").use { st ->
            st.bindFilters()
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        val payments = conn.prepareStatement(
            "SELECT * FROM ($base) x $statusFilter ORDER BY captured_at DESC, recorded_at DESC, payment_id LIMIT ? OFFSET ?",
        ).use { st ->
            st.bindFilters()
            st.setInt(5, limit)
            st.setInt(6, offset)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toPayment()) } }
        }
        return total to payments.map { PaymentDetails(it, refundsOf(conn, it.paymentId)) }
    }

    fun details(conn: Connection, paymentId: String): PaymentDetails? =
        findPayment(conn, paymentId)?.let { PaymentDetails(it.value, refundsOf(conn, paymentId)) }

    private fun ResultSet.toPayment() = Payment(
        paymentId = getString("payment_id"),
        merchantId = getString("merchant_id"),
        currency = getString("currency"),
        split = Split(getLong("tax_amount"), getLong("fee_amount"), getLong("merchant_amount")),
        taxRate = getBigDecimal("tax_rate").stripTrailingZeros(),
        taxJurisdiction = getString("tax_jurisdiction"),
        feeRate = getBigDecimal("fee_rate").stripTrailingZeros(),
        refundFeePolicy = RefundFeePolicy.valueOf(getString("refund_fee_policy")),
        capturedAt = getObject("captured_at", OffsetDateTime::class.java).toInstant(),
        recordedAt = getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
    )

    private fun ResultSet.toRefund() = Refund(
        refundId = getString("refund_id"),
        paymentId = getString("payment_id"),
        split = Split(getLong("tax_amount"), getLong("fee_amount"), getLong("merchant_amount")),
        refundedAt = getObject("refunded_at", OffsetDateTime::class.java).toInstant(),
        recordedAt = getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
    )
}
