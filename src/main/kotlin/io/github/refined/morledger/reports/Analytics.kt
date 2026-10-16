package io.github.refined.morledger.reports

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.ChartOfAccounts
import io.github.refined.morledger.ledger.Ledger
import io.github.refined.morledger.payments.NotFoundException
import io.github.refined.morledger.payments.ValidationException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

data class Flow(val count: Long, val gross: Long, val tax: Long, val fee: Long, val merchant: Long) {
    operator fun minus(o: Flow) = Flow(count - o.count, gross - o.gross, tax - o.tax, fee - o.fee, merchant - o.merchant)

    companion object {
        val ZERO = Flow(0, 0, 0, 0, 0)
    }
}

data class CurrencySummary(
    val currency: String,
    val merchants: Long,
    val captured: Flow,
    val refunded: Flow,
    val byJurisdiction: List<JurisdictionSummary>,
) {
    val net: Flow get() = captured - refunded
}

data class JurisdictionSummary(val jurisdiction: String, val captured: Flow, val refunded: Flow)

data class MerchantSummary(
    val merchantId: String,
    val currency: String,
    val captured: Flow,
    val refunded: Flow,
    val firstPaymentAt: Instant,
    val lastPaymentAt: Instant,
    val ledgerBalance: Long?,
) {
    val balanceFromRecords: Long get() = captured.merchant - refunded.merchant
    val reconciled: Boolean get() = ledgerBalance == balanceFromRecords
}

data class MerchantPage(val total: Long, val rows: List<MerchantSummary>)

data class MerchantDetail(val merchantId: String, val currencies: List<MerchantSummary>, val byJurisdiction: Map<String, List<JurisdictionSummary>>)

enum class Bucket(val sql: String) { DAY("day"), WEEK("week"), MONTH("month") }

data class TimePoint(val start: LocalDate, val captured: Flow, val refunded: Flow, val merchantBalance: Long)

enum class MerchantSort(val sql: String) {
    GROSS("c_gross"),
    PAYMENTS("c_count"),
    REFUND_RATE("refund_rate"),
    REFUNDED("r_gross"),
    FEE("net_fee"),
    BALANCE("balance"),
    LAST_PAYMENT("last_at"),
    MERCHANT("merchant_id"),
}

class Analytics(private val dataSource: DataSource, private val ledger: Ledger = Ledger()) {

    fun summary(currency: String?, from: Instant?, to: Instant?): List<CurrencySummary> = dataSource.transaction { conn ->
        val captured = flows(conn, "p.currency", currency, null, from, to, refunds = false).associateBy { it.first }
        val refunded = flows(conn, "p.currency", currency, null, from, to, refunds = true).associateBy { it.first }
        val capturedByJ = flows(conn, "p.currency || '|' || p.tax_jurisdiction", currency, null, from, to, refunds = false)
        val refundedByJ = flows(conn, "p.currency || '|' || p.tax_jurisdiction", currency, null, from, to, refunds = true).toMap()
        val merchants = conn.query(
            "SELECT currency, count(DISTINCT merchant_id) FROM payments p WHERE ${filters(currency, null, from, to, "p.captured_at")} GROUP BY currency",
            filterParams(currency, null, from, to),
        ) { it.getString(1) to it.getLong(2) }.toMap()

        (captured.keys + refunded.keys).sorted().map { cur ->
            CurrencySummary(
                currency = cur,
                merchants = merchants[cur] ?: 0,
                captured = captured[cur]?.second ?: Flow.ZERO,
                refunded = refunded[cur]?.second ?: Flow.ZERO,
                byJurisdiction = capturedByJ.filter { it.first.startsWith("$cur|") }.map { (key, flow) ->
                    JurisdictionSummary(key.substringAfter('|'), flow, refundedByJ[key] ?: Flow.ZERO)
                }.sortedByDescending { it.captured.tax },
            )
        }
    }

    fun timeseries(currency: String, merchantId: String?, bucket: Bucket, from: Instant?, to: Instant?): List<TimePoint> =
        dataSource.transaction { conn ->
            fun series(refunds: Boolean): Map<LocalDate, Flow> {
                val time = if (refunds) "r.refunded_at" else "p.captured_at"
                return flows(conn, "date_trunc('${bucket.sql}', $time AT TIME ZONE 'UTC')::date::text", currency, merchantId, from, to, refunds)
                    .associate { LocalDate.parse(it.first) to it.second }
            }
            val captured = series(refunds = false)
            val refunded = series(refunds = true)
            if (captured.isEmpty() && refunded.isEmpty()) return@transaction emptyList()

            var balance = if (from == null) 0L else {
                val before = flows(conn, "'x'", currency, merchantId, null, from, refunds = false).firstOrNull()?.second ?: Flow.ZERO
                val beforeRefunds = flows(conn, "'x'", currency, merchantId, null, from, refunds = true).firstOrNull()?.second ?: Flow.ZERO
                before.merchant - beforeRefunds.merchant
            }
            val first = (captured.keys + refunded.keys).min()
            val last = (captured.keys + refunded.keys).max()
            generateSequence(first) { next(it, bucket) }.takeWhile { !it.isAfter(last) }.map { start ->
                val c = captured[start] ?: Flow.ZERO
                val r = refunded[start] ?: Flow.ZERO
                balance += c.merchant - r.merchant
                TimePoint(start, c, r, balance)
            }.toList()
        }

    fun merchants(currency: String?, search: String?, sort: MerchantSort, descending: Boolean, limit: Int, offset: Int): MerchantPage =
        dataSource.transaction { conn ->
            val where = buildList {
                if (currency != null) add("c.currency = ?")
                if (search != null) add("c.merchant_id ILIKE ?")
            }.joinToString(" AND ").ifEmpty { "TRUE" }
            val params = listOfNotNull(currency, search?.let { "%$it%" })
            val total = conn.query("SELECT count(*) FROM (${merchantRowsSql()}) c WHERE $where", params) { it.getLong(1) }.single()
            val direction = if (descending) "DESC" else "ASC"
            val rows = conn.query(
                "SELECT * FROM (${merchantRowsSql()}) c WHERE $where ORDER BY ${sort.sql} $direction NULLS LAST, merchant_id, currency LIMIT ? OFFSET ?",
                params + listOf(limit, offset),
            ) { it.toMerchantSummary() }
            MerchantPage(total, withLedgerBalances(conn, rows))
        }

    fun merchant(merchantId: String): MerchantDetail = dataSource.transaction { conn ->
        val rows = conn.query("SELECT * FROM (${merchantRowsSql()}) c WHERE merchant_id = ? ORDER BY c_gross DESC", listOf(merchantId)) {
            it.toMerchantSummary()
        }
        if (rows.isEmpty()) throw NotFoundException("merchant_not_found", "merchant $merchantId has no payments")
        val captured = flows(conn, "p.currency || '|' || p.tax_jurisdiction", null, merchantId, null, null, refunds = false)
        val refunded = flows(conn, "p.currency || '|' || p.tax_jurisdiction", null, merchantId, null, null, refunds = true).toMap()
        val byJurisdiction = captured
            .map { (key, flow) -> key.substringBefore('|') to JurisdictionSummary(key.substringAfter('|'), flow, refunded[key] ?: Flow.ZERO) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedByDescending { it.captured.gross } }
        MerchantDetail(merchantId, withLedgerBalances(conn, rows), byJurisdiction)
    }

    private fun withLedgerBalances(conn: Connection, rows: List<MerchantSummary>): List<MerchantSummary> {
        if (rows.isEmpty()) return rows
        val balances = ledger.balances(conn)
            .filter { it.account.code.startsWith(ChartOfAccounts.MERCHANT_PAYABLE_PREFIX) }
            .associate { (it.account.code.removePrefix(ChartOfAccounts.MERCHANT_PAYABLE_PREFIX) to it.account.currency) to it.balance }
        return rows.map { it.copy(ledgerBalance = balances[it.merchantId to it.currency]) }
    }


    private fun merchantRowsSql() = """
        SELECT cap.merchant_id, cap.currency,
               cap.c_count, cap.c_gross, cap.c_tax, cap.c_fee, cap.c_merchant, cap.first_at, cap.last_at,
               COALESCE(ref.r_count, 0) AS r_count, COALESCE(ref.r_gross, 0) AS r_gross, COALESCE(ref.r_tax, 0) AS r_tax,
               COALESCE(ref.r_fee, 0) AS r_fee, COALESCE(ref.r_merchant, 0) AS r_merchant,
               COALESCE(ref.r_gross, 0)::numeric / NULLIF(cap.c_gross, 0) AS refund_rate,
               cap.c_fee - COALESCE(ref.r_fee, 0) AS net_fee,
               cap.c_merchant - COALESCE(ref.r_merchant, 0) AS balance
        FROM (SELECT merchant_id, currency, count(*) AS c_count, sum(amount) AS c_gross, sum(tax_amount) AS c_tax,
                     sum(fee_amount) AS c_fee, sum(merchant_amount) AS c_merchant,
                     min(captured_at) AS first_at, max(captured_at) AS last_at
              FROM payments GROUP BY merchant_id, currency) cap
        LEFT JOIN (SELECT p.merchant_id, p.currency, count(*) AS r_count, sum(r.amount) AS r_gross, sum(r.tax_amount) AS r_tax,
                          sum(r.fee_amount) AS r_fee, sum(r.merchant_amount) AS r_merchant
                   FROM refunds r JOIN payments p ON p.payment_id = r.payment_id
                   GROUP BY p.merchant_id, p.currency) ref
          ON ref.merchant_id = cap.merchant_id AND ref.currency = cap.currency
    """.trimIndent()

    private fun ResultSet.toMerchantSummary() = MerchantSummary(
        merchantId = getString("merchant_id"),
        currency = getString("currency"),
        captured = Flow(getLong("c_count"), getLong("c_gross"), getLong("c_tax"), getLong("c_fee"), getLong("c_merchant")),
        refunded = Flow(getLong("r_count"), getLong("r_gross"), getLong("r_tax"), getLong("r_fee"), getLong("r_merchant")),
        firstPaymentAt = getObject("first_at", OffsetDateTime::class.java).toInstant(),
        lastPaymentAt = getObject("last_at", OffsetDateTime::class.java).toInstant(),
        ledgerBalance = null,
    )

    private fun flows(
        conn: Connection,
        key: String,
        currency: String?,
        merchantId: String?,
        from: Instant?,
        to: Instant?,
        refunds: Boolean,
    ): List<Pair<String, Flow>> {
        val sql = if (refunds) {
            """
            SELECT $key AS k, count(*), sum(r.amount), sum(r.tax_amount), sum(r.fee_amount), sum(r.merchant_amount)
            FROM refunds r JOIN payments p ON p.payment_id = r.payment_id
            WHERE ${filters(currency, merchantId, from, to, "r.refunded_at")}
            GROUP BY 1 ORDER BY 1
            """
        } else {
            """
            SELECT $key AS k, count(*), sum(p.amount), sum(p.tax_amount), sum(p.fee_amount), sum(p.merchant_amount)
            FROM payments p
            WHERE ${filters(currency, merchantId, from, to, "p.captured_at")}
            GROUP BY 1 ORDER BY 1
            """
        }
        return conn.query(sql.trimIndent(), filterParams(currency, merchantId, from, to)) {
            it.getString(1) to Flow(it.getLong(2), it.getLong(3), it.getLong(4), it.getLong(5), it.getLong(6))
        }
    }

    private fun filters(currency: String?, merchantId: String?, from: Instant?, to: Instant?, timeColumn: String) =
        buildList {
            if (currency != null) add("p.currency = ?")
            if (merchantId != null) add("p.merchant_id = ?")
            if (from != null) add("$timeColumn >= ?")
            if (to != null) add("$timeColumn < ?")
        }.joinToString(" AND ").ifEmpty { "TRUE" }

    private fun filterParams(currency: String?, merchantId: String?, from: Instant?, to: Instant?): List<Any> =
        listOfNotNull(currency, merchantId, from?.atOffset(ZoneOffset.UTC), to?.atOffset(ZoneOffset.UTC))

    private fun <T> Connection.query(sql: String, params: List<Any>, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { st ->
            st.bind(params)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    private fun PreparedStatement.bind(params: List<Any>) = params.forEachIndexed { i, v -> setObject(i + 1, v) }

    companion object {
        fun next(start: LocalDate, bucket: Bucket): LocalDate = when (bucket) {
            Bucket.DAY -> start.plusDays(1)
            Bucket.WEEK -> start.plusWeeks(1)
            Bucket.MONTH -> start.plusMonths(1)
        }

        fun parseBucket(value: String?): Bucket = when (value?.lowercase()) {
            null, "day" -> Bucket.DAY
            "week" -> Bucket.WEEK
            "month" -> Bucket.MONTH
            else -> throw ValidationException("bucket must be day, week or month")
        }

        fun parseSort(value: String?): MerchantSort = when (value) {
            null, "gross" -> MerchantSort.GROSS
            "payments" -> MerchantSort.PAYMENTS
            "refundRate" -> MerchantSort.REFUND_RATE
            "refunded" -> MerchantSort.REFUNDED
            "fee" -> MerchantSort.FEE
            "balance" -> MerchantSort.BALANCE
            "lastPayment" -> MerchantSort.LAST_PAYMENT
            "merchant" -> MerchantSort.MERCHANT
            else -> throw ValidationException("sort must be one of gross, payments, refundRate, refunded, fee, balance, lastPayment, merchant")
        }
    }
}
