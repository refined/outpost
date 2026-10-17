package io.github.refined.morledger.payments

import io.github.refined.morledger.db.instant
import io.github.refined.morledger.db.query
import io.github.refined.morledger.db.sql
import io.github.refined.morledger.db.update
import io.github.refined.morledger.money.Split
import java.sql.Connection
import java.sql.ResultSet

internal object RefundsTable {
    private const val COLUMNS = "refund_id, payment_id, tax_amount, fee_amount, merchant_amount, refunded_at, recorded_at, fingerprint"

    fun insert(conn: Connection, refund: Refund, fingerprint: String): Boolean = conn.update(sql {
        """
        INSERT INTO refunds (refund_id, payment_id, amount, tax_amount, fee_amount, merchant_amount, refunded_at, fingerprint)
        VALUES (${param(refund.refundId)}, ${param(refund.paymentId)}, ${param(refund.split.total)},
                ${param(refund.split.tax)}, ${param(refund.split.fee)}, ${param(refund.split.merchant)},
                ${param(refund.refundedAt)}, ${param(fingerprint)})
        ON CONFLICT (refund_id) DO NOTHING
        """
    }) == 1

    fun find(conn: Connection, refundId: String): Stored<Refund>? = conn.query(sql {
        """
        SELECT $COLUMNS
        FROM refunds
        WHERE refund_id = ${param(refundId)}
        """
    }) { Stored(it.toRefund(), it.getString("fingerprint")) }.singleOrNull()

    fun forPayment(conn: Connection, paymentId: String): List<Refund> = conn.query(sql {
        """
        SELECT $COLUMNS
        FROM refunds
        WHERE payment_id = ${param(paymentId)}
        ORDER BY recorded_at, refund_id
        """
    }) { it.toRefund() }

    private fun ResultSet.toRefund() = Refund(
        refundId = getString("refund_id"),
        paymentId = getString("payment_id"),
        split = Split(getLong("tax_amount"), getLong("fee_amount"), getLong("merchant_amount")),
        refundedAt = instant("refunded_at"),
        recordedAt = instant("recorded_at"),
    )
}
