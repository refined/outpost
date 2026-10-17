package io.github.refined.morledger.payments

import io.github.refined.morledger.db.instant
import io.github.refined.morledger.db.query
import io.github.refined.morledger.db.sql
import io.github.refined.morledger.db.update
import io.github.refined.morledger.db.where
import io.github.refined.morledger.money.RefundFeePolicy
import io.github.refined.morledger.money.Split
import java.sql.Connection
import java.sql.ResultSet

internal object PaymentsTable {
    private const val COLUMNS = """
        payment_id, merchant_id, currency, tax_amount, fee_amount, merchant_amount, tax_rate, tax_jurisdiction,
        fee_rate, refund_fee_policy, captured_at, recorded_at, fingerprint
    """

    fun insert(conn: Connection, payment: Payment, fingerprint: String): Boolean = conn.update(sql {
        """
        INSERT INTO payments (payment_id, merchant_id, currency, amount, tax_amount, fee_amount, merchant_amount,
                              tax_rate, tax_jurisdiction, fee_rate, refund_fee_policy, captured_at, fingerprint)
        VALUES (${param(payment.paymentId)}, ${param(payment.merchantId)}, ${param(payment.currency)}, ${param(payment.amount)},
                ${param(payment.split.tax)}, ${param(payment.split.fee)}, ${param(payment.split.merchant)},
                ${param(payment.taxRate)}, ${param(payment.taxJurisdiction)}, ${param(payment.feeRate)},
                ${param(payment.refundFeePolicy.name)}, ${param(payment.capturedAt)}, ${param(fingerprint)})
        ON CONFLICT (payment_id) DO NOTHING
        """
    }) == 1

    fun find(conn: Connection, paymentId: String, forUpdate: Boolean = false): Stored<Payment>? = conn.query(sql {
        """
        SELECT $COLUMNS
        FROM payments
        WHERE payment_id = ${param(paymentId)}
        ${if (forUpdate) "FOR UPDATE" else ""}
        """
    }) { Stored(it.toPayment(), it.getString("fingerprint")) }.singleOrNull()

    fun page(conn: Connection, merchantId: String?, status: PaymentStatus?, limit: Int, offset: Int): PaymentRows {
        val base = """
            SELECT p.*, CASE WHEN COALESCE(r.refunded, 0) = 0 THEN 'CAPTURED'
                             WHEN r.refunded < p.amount THEN 'PARTIALLY_REFUNDED'
                             ELSE 'REFUNDED' END AS status
            FROM payments p
            LEFT JOIN (
                SELECT payment_id, sum(amount) AS refunded
                FROM refunds
                GROUP BY payment_id
            ) r ON r.payment_id = p.payment_id
        """.trimIndent()
        val total = conn.query(sql {
            """
            SELECT count(*)
            FROM ($base) x
            WHERE ${where {
                if (merchantId != null) add("merchant_id = ${param(merchantId)}")
                if (status != null) add("status = ${param(status.name)}")
            }}
            """
        }) { it.getLong(1) }.single()
        val rows = conn.query(sql {
            """
            SELECT *
            FROM ($base) x
            WHERE ${where {
                if (merchantId != null) add("merchant_id = ${param(merchantId)}")
                if (status != null) add("status = ${param(status.name)}")
            }}
            ORDER BY captured_at DESC, recorded_at DESC, payment_id
            LIMIT ${param(limit)} OFFSET ${param(offset)}
            """
        }) { it.toPayment() }
        return PaymentRows(total, rows)
    }

    private fun ResultSet.toPayment() = Payment(
        paymentId = getString("payment_id"),
        merchantId = getString("merchant_id"),
        currency = getString("currency"),
        split = Split(getLong("tax_amount"), getLong("fee_amount"), getLong("merchant_amount")),
        taxRate = getBigDecimal("tax_rate").stripTrailingZeros(),
        taxJurisdiction = getString("tax_jurisdiction"),
        feeRate = getBigDecimal("fee_rate").stripTrailingZeros(),
        refundFeePolicy = RefundFeePolicy.valueOf(getString("refund_fee_policy")),
        capturedAt = instant("captured_at"),
        recordedAt = instant("recorded_at"),
    )
}

internal data class PaymentRows(val total: Long, val rows: List<Payment>)
