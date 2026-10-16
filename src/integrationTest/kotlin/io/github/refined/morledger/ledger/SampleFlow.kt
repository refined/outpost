package io.github.refined.morledger.ledger

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.payments.CaptureCommand
import io.github.refined.morledger.payments.PaymentService
import io.github.refined.morledger.payments.RefundCommand
import io.github.refined.morledger.reports.BalanceReport
import io.github.refined.morledger.reports.Balances
import java.math.BigDecimal
import java.time.Instant

/** One fixed money flow (12 captures in two currencies, partial and full refunds), run against any engine. */
object SampleFlow {
    val t0: Instant = Instant.parse("2026-10-01T10:00:00Z")

    /**
     * Resets the database, runs the flow and returns the current balances report. A refreshing engine is refreshed
     * after the captures, so its reads mix snapshot and tail, and again before the report when [refreshAtEnd].
     */
    fun run(engine: LedgerEngine, hotAccountShards: Int = 1, refreshAtEnd: Boolean = true): Balances {
        TestDatabase.reset()
        val service = PaymentService(TestDatabase.dataSource, ledger = engine, hotAccountShards = hotAccountShards)
        for (i in 1..12) {
            val merchant = if (i % 3 == 0) "m_a" else "m_b"
            val (currency, jurisdiction, rate) = if (i % 4 == 0) Triple("USD", "US-CA", "0.0725") else Triple("EUR", "NL", "0.21")
            service.capture(CaptureCommand("p_$i", merchant, 1_000L * i + 17, currency, BigDecimal(rate), jurisdiction, t0.plusSeconds(i * 60L)))
        }
        refresh(engine)
        service.refund(RefundCommand("p_3", "r_3a", 1_000, t0.plusSeconds(3_600)))
        service.refund(RefundCommand("p_3", "r_3b", null, t0.plusSeconds(7_200)))
        service.refund(RefundCommand("p_8", "r_8", 2_500, t0.plusSeconds(3_600)))
        if (refreshAtEnd) refresh(engine)
        return BalanceReport(TestDatabase.dataSource, engine).build()
    }

    fun refresh(engine: LedgerEngine) {
        if (engine.refreshesBalances) TestDatabase.dataSource.transaction { engine.refreshBalances(it) }
    }

    /** The asOf report for the flow just run (between the first and the last refund). */
    fun asOf(engine: LedgerEngine): Balances = BalanceReport(TestDatabase.dataSource, engine).build(asOf = t0.plusSeconds(5_000))
}
