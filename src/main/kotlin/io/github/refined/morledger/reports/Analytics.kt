package io.github.refined.morledger.reports

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.ChartOfAccounts
import io.github.refined.morledger.ledger.LedgerTable
import io.github.refined.morledger.payments.NotFoundException
import io.github.refined.morledger.payments.ValidationException
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
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

class Analytics(private val dataSource: DataSource) {

    fun summary(currency: String?, from: Instant?, to: Instant?): List<CurrencySummary> = dataSource.transaction { conn ->
        val captured = AnalyticsTable.byCurrency(conn, currency, null, from, to, refunds = false).toMap()
        val refunded = AnalyticsTable.byCurrency(conn, currency, null, from, to, refunds = true).toMap()
        val capturedByJ = AnalyticsTable.byJurisdiction(conn, currency, null, from, to, refunds = false)
        val refundedByJ = AnalyticsTable.byJurisdiction(conn, currency, null, from, to, refunds = true).toMap()
        val merchants = AnalyticsTable.merchantCounts(conn, currency, from, to)

        (captured.keys + refunded.keys).sorted().map { cur ->
            CurrencySummary(
                currency = cur,
                merchants = merchants[cur] ?: 0,
                captured = captured[cur] ?: Flow.ZERO,
                refunded = refunded[cur] ?: Flow.ZERO,
                byJurisdiction = capturedByJ.filter { it.first.startsWith("$cur|") }.map { (key, flow) ->
                    JurisdictionSummary(key.substringAfter('|'), flow, refundedByJ[key] ?: Flow.ZERO)
                }.sortedByDescending { it.captured.tax },
            )
        }
    }

    fun timeseries(currency: String, merchantId: String?, bucket: Bucket, from: Instant?, to: Instant?): List<TimePoint> =
        dataSource.transaction { conn ->
            fun series(refunds: Boolean): Map<LocalDate, Flow> =
                AnalyticsTable.byBucket(conn, currency, merchantId, bucket, from, to, refunds)
            val captured = series(refunds = false)
            val refunded = series(refunds = true)
            if (captured.isEmpty() && refunded.isEmpty()) return@transaction emptyList()

            var balance = if (from == null) 0L else {
                val before = AnalyticsTable.totalFlow(conn, currency, merchantId, null, from, refunds = false)
                val beforeRefunds = AnalyticsTable.totalFlow(conn, currency, merchantId, null, from, refunds = true)
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
            val page = AnalyticsTable.merchants(conn, currency, search, sort, descending, limit, offset)
            page.copy(rows = withLedgerBalances(conn, page.rows))
        }

    fun merchant(merchantId: String): MerchantDetail = dataSource.transaction { conn ->
        val rows = AnalyticsTable.merchant(conn, merchantId)
        if (rows.isEmpty()) throw NotFoundException("merchant_not_found", "merchant $merchantId has no payments")
        val captured = AnalyticsTable.byJurisdiction(conn, null, merchantId, null, null, refunds = false)
        val refunded = AnalyticsTable.byJurisdiction(conn, null, merchantId, null, null, refunds = true).toMap()
        val byJurisdiction = captured
            .map { (key, flow) -> key.substringBefore('|') to JurisdictionSummary(key.substringAfter('|'), flow, refunded[key] ?: Flow.ZERO) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedByDescending { it.captured.gross } }
        MerchantDetail(merchantId, withLedgerBalances(conn, rows), byJurisdiction)
    }

    private fun withLedgerBalances(conn: Connection, rows: List<MerchantSummary>): List<MerchantSummary> {
        if (rows.isEmpty()) return rows
        val balances = LedgerTable.balances(conn)
            .filter { it.account.code.startsWith(ChartOfAccounts.MERCHANT_PAYABLE_PREFIX) }
            .associate { (it.account.code.removePrefix(ChartOfAccounts.MERCHANT_PAYABLE_PREFIX) to it.account.currency) to it.balance }
        return rows.map { it.copy(ledgerBalance = balances[it.merchantId to it.currency]) }
    }

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
