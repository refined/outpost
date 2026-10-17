package io.github.refined.morledger.reports

import io.github.refined.morledger.db.instant
import io.github.refined.morledger.db.query
import io.github.refined.morledger.db.sql
import io.github.refined.morledger.db.where
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate

internal object AnalyticsTable {
    fun merchantCounts(conn: Connection, currency: String?, from: Instant?, to: Instant?): Map<String, Long> = conn.query(sql {
        """
        SELECT currency, count(DISTINCT merchant_id)
        FROM payments p
        WHERE ${where {
            if (currency != null) add("p.currency = ${param(currency)}")
            if (from != null) add("p.captured_at >= ${param(from)}")
            if (to != null) add("p.captured_at < ${param(to)}")
        }}
        GROUP BY currency
        """
    }) { it.getString(1) to it.getLong(2) }.toMap()

    fun merchants(
        conn: Connection,
        currency: String?,
        search: String?,
        sort: MerchantSort,
        descending: Boolean,
        limit: Int,
        offset: Int,
    ): MerchantPage {
        val total = conn.query(sql {
            """
            SELECT count(*)
            FROM (${merchantRowsSql()}) c
            WHERE ${where {
                if (currency != null) add("c.currency = ${param(currency)}")
                if (search != null) add("c.merchant_id ILIKE ${param("%$search%")}")
            }}
            """
        }) { it.getLong(1) }.single()
        val direction = if (descending) "DESC" else "ASC"
        val rows = conn.query(sql {
            """
            SELECT *
            FROM (${merchantRowsSql()}) c
            WHERE ${where {
                if (currency != null) add("c.currency = ${param(currency)}")
                if (search != null) add("c.merchant_id ILIKE ${param("%$search%")}")
            }}
            ORDER BY ${sort.sql} $direction NULLS LAST, merchant_id, currency
            LIMIT ${param(limit)} OFFSET ${param(offset)}
            """
        }) { it.toMerchantSummary() }
        return MerchantPage(total, rows)
    }

    fun merchant(conn: Connection, merchantId: String): List<MerchantSummary> = conn.query(sql {
        """
        SELECT *
        FROM (${merchantRowsSql()}) c
        WHERE merchant_id = ${param(merchantId)}
        ORDER BY c_gross DESC
        """
    }) { it.toMerchantSummary() }

    fun byCurrency(conn: Connection, currency: String?, merchantId: String?, from: Instant?, to: Instant?, refunds: Boolean) =
        if (refunds) refundFlows(conn, "p.currency", currency, merchantId, from, to)
        else paymentFlows(conn, "p.currency", currency, merchantId, from, to)

    fun byJurisdiction(conn: Connection, currency: String?, merchantId: String?, from: Instant?, to: Instant?, refunds: Boolean): List<Pair<String, Flow>> {
        val key = "p.currency || '|' || p.tax_jurisdiction"
        return if (refunds) refundFlows(conn, key, currency, merchantId, from, to)
        else paymentFlows(conn, key, currency, merchantId, from, to)
    }

    fun byBucket(
        conn: Connection,
        currency: String,
        merchantId: String?,
        bucket: Bucket,
        from: Instant?,
        to: Instant?,
        refunds: Boolean,
    ): Map<LocalDate, Flow> {
        val time = if (refunds) "r.refunded_at" else "p.captured_at"
        val key = "date_trunc('${bucket.sql}', $time AT TIME ZONE 'UTC')::date::text"
        val rows = if (refunds) refundFlows(conn, key, currency, merchantId, from, to)
        else paymentFlows(conn, key, currency, merchantId, from, to)
        return rows.associate { LocalDate.parse(it.first) to it.second }
    }

    fun totalFlow(conn: Connection, currency: String, merchantId: String?, from: Instant?, to: Instant?, refunds: Boolean): Flow {
        val rows = if (refunds) refundFlows(conn, "'x'", currency, merchantId, from, to)
        else paymentFlows(conn, "'x'", currency, merchantId, from, to)
        return rows.firstOrNull()?.second ?: Flow.ZERO
    }

    private fun paymentFlows(
        conn: Connection,
        key: String,
        currency: String?,
        merchantId: String?,
        from: Instant?,
        to: Instant?,
    ): List<Pair<String, Flow>> = conn.query(sql {
        """
        SELECT $key AS k, count(*), sum(p.amount), sum(p.tax_amount), sum(p.fee_amount), sum(p.merchant_amount)
        FROM payments p
        WHERE ${where {
            if (currency != null) add("p.currency = ${param(currency)}")
            if (merchantId != null) add("p.merchant_id = ${param(merchantId)}")
            if (from != null) add("p.captured_at >= ${param(from)}")
            if (to != null) add("p.captured_at < ${param(to)}")
        }}
        GROUP BY 1
        ORDER BY 1
        """
    }) { it.getString(1) to Flow(it.getLong(2), it.getLong(3), it.getLong(4), it.getLong(5), it.getLong(6)) }

    private fun refundFlows(
        conn: Connection,
        key: String,
        currency: String?,
        merchantId: String?,
        from: Instant?,
        to: Instant?,
    ): List<Pair<String, Flow>> = conn.query(sql {
        """
        SELECT $key AS k, count(*), sum(r.amount), sum(r.tax_amount), sum(r.fee_amount), sum(r.merchant_amount)
        FROM refunds r
        JOIN payments p ON p.payment_id = r.payment_id
        WHERE ${where {
            if (currency != null) add("p.currency = ${param(currency)}")
            if (merchantId != null) add("p.merchant_id = ${param(merchantId)}")
            if (from != null) add("r.refunded_at >= ${param(from)}")
            if (to != null) add("r.refunded_at < ${param(to)}")
        }}
        GROUP BY 1
        ORDER BY 1
        """
    }) { it.getString(1) to Flow(it.getLong(2), it.getLong(3), it.getLong(4), it.getLong(5), it.getLong(6)) }

    private fun merchantRowsSql() = """
        SELECT cap.merchant_id, cap.currency,
               cap.c_count, cap.c_gross, cap.c_tax, cap.c_fee, cap.c_merchant, cap.first_at, cap.last_at,
               COALESCE(ref.r_count, 0) AS r_count, COALESCE(ref.r_gross, 0) AS r_gross, COALESCE(ref.r_tax, 0) AS r_tax,
               COALESCE(ref.r_fee, 0) AS r_fee, COALESCE(ref.r_merchant, 0) AS r_merchant,
               COALESCE(ref.r_gross, 0)::numeric / NULLIF(cap.c_gross, 0) AS refund_rate,
               cap.c_fee - COALESCE(ref.r_fee, 0) AS net_fee,
               cap.c_merchant - COALESCE(ref.r_merchant, 0) AS balance
        FROM (
            SELECT merchant_id, currency, count(*) AS c_count, sum(amount) AS c_gross, sum(tax_amount) AS c_tax,
                   sum(fee_amount) AS c_fee, sum(merchant_amount) AS c_merchant,
                   min(captured_at) AS first_at, max(captured_at) AS last_at
            FROM payments
            GROUP BY merchant_id, currency
        ) cap
        LEFT JOIN (
            SELECT p.merchant_id, p.currency, count(*) AS r_count, sum(r.amount) AS r_gross, sum(r.tax_amount) AS r_tax,
                   sum(r.fee_amount) AS r_fee, sum(r.merchant_amount) AS r_merchant
            FROM refunds r
            JOIN payments p ON p.payment_id = r.payment_id
            GROUP BY p.merchant_id, p.currency
        ) ref ON ref.merchant_id = cap.merchant_id AND ref.currency = cap.currency
    """.trimIndent()

    private fun ResultSet.toMerchantSummary() = MerchantSummary(
        merchantId = getString("merchant_id"),
        currency = getString("currency"),
        captured = Flow(getLong("c_count"), getLong("c_gross"), getLong("c_tax"), getLong("c_fee"), getLong("c_merchant")),
        refunded = Flow(getLong("r_count"), getLong("r_gross"), getLong("r_tax"), getLong("r_fee"), getLong("r_merchant")),
        firstPaymentAt = instant("first_at"),
        lastPaymentAt = instant("last_at"),
        ledgerBalance = null,
    )

}
