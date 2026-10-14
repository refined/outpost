package io.github.refined.morledger.payments

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.Ledger
import io.github.refined.morledger.reports.BalanceReport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrencyTest {
    private val service = PaymentService(TestDatabase.dataSource)

    @BeforeEach
    fun reset() = TestDatabase.reset()

    private fun capture(id: String, amount: Long = 10_000) = service.capture(
        CaptureCommand(id, "m_1", amount, "EUR", BigDecimal("0.21"), "NL", Instant.parse("2026-10-01T10:00:00Z")),
    )

    private fun <T> parallel(n: Int, task: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(n)
        try {
            return pool.invokeAll((0 until n).map { i -> Callable { runCatching { task(i) } } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `parallel partial refunds never exceed the captured amount`() {
        capture("p_1", amount = 10_000)

        // 20 refunds of 700 race for 10_000: exactly 14 fit, the rest must be rejected.
        val results = parallel(20) { i -> service.refund(RefundCommand("p_1", "r_$i", 700, null)) }

        assertEquals(14, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is UnprocessableException })
        val details = service.get("p_1")
        assertEquals(9_800, details.refunded.total)
        assertTrue(BalanceReport(TestDatabase.dataSource).build().integrity.balanced)
        TestDatabase.assertLedgerConsistent()
    }

    @Test
    fun `parallel captures on shared accounts lose no update and do not deadlock`() {
        // Every capture touches psp_clearing, fee_revenue and tax_payable:NL; half also share one merchant account.
        val results = parallel(16) { i ->
            CaptureCommand("p_$i", if (i % 2 == 0) "m_shared" else "m_$i", 10_000, "EUR", BigDecimal("0.21"), "NL", Instant.parse("2026-10-01T10:00:00Z"))
                .let { service.capture(it) }
        }

        assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        val report = BalanceReport(TestDatabase.dataSource).build()
        assertEquals(16 * 10_000L, report.mor.single().pspClearing)
        assertEquals(16 * 500L, report.mor.single().feeRevenue)
        assertEquals(8 * 7_764L, report.merchants.single { it.merchantId == "m_shared" }.balance)
        TestDatabase.assertLedgerConsistent()
    }

    @Test
    fun `parallel duplicate captures record one payment and one journal entry`() {
        val results = parallel(10) { capture("p_dup") }

        assertTrue(results.all { it.isSuccess })
        assertEquals(1, results.count { it.getOrThrow().created })
        assertEquals(1, TestDatabase.dataSource.transaction { Ledger.entries(it, "p_dup") }.size)
    }

    @Test
    fun `parallel replays of one refund post it once`() {
        capture("p_1")

        val results = parallel(10) { service.refund(RefundCommand("p_1", "r_same", 500, null)) }

        assertTrue(results.all { it.isSuccess })
        assertEquals(1, results.count { it.getOrThrow().created })
        assertEquals(500, service.get("p_1").refunded.total)
    }
}
