package io.github.refined.morledger.ledger

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.payments.CaptureCommand
import io.github.refined.morledger.payments.PaymentService
import io.github.refined.morledger.payments.RefundCommand
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The balance strategies and hot-account sharding are performance knobs: they must never change the numbers. */
class LedgerModesTest {
    private val dataSource = TestDatabase.dataSource
    private val t0 = SampleFlow.t0

    private data class Variant(val name: String, val mode: BalanceMode, val shards: Int)

    private val variants = listOf(
        Variant("stored", BalanceMode.STORED, 1),
        Variant("computed", BalanceMode.COMPUTED, 1),
        Variant("stored + 4 shards", BalanceMode.STORED, 4),
        Variant("computed + 4 shards", BalanceMode.COMPUTED, 4),
    )

    @Test
    fun `every balance mode and shard count reports the same balances`() {
        val results = variants.associate { v ->
            val engine = PostgresLedger(v.mode)
            val now = SampleFlow.run(engine, v.shards)
            assertEquals(emptyList(), dataSource.transaction { engine.verify(it) }, v.name)
            v.name to (now to SampleFlow.asOf(engine))
        }
        val (expectedNow, expectedAsOf) = results.getValue("stored")
        assertTrue(expectedNow.integrity.balanced)
        assertTrue(expectedNow.merchants.isNotEmpty() && expectedNow.taxAuthorities.isNotEmpty())

        for ((name, result) in results) {
            assertEquals(expectedNow, result.first, "current balances, $name")
            assertEquals(expectedAsOf, result.second, "asOf balances, $name")
        }
    }

    @Test
    fun `shards spread hot accounts and a refund lands on its capture's shard`() {
        TestDatabase.reset()
        val engine = PostgresLedger(BalanceMode.STORED)
        val service = PaymentService(dataSource, ledger = engine, hotAccountShards = 8)
        for (i in 1..40) service.capture(CaptureCommand("p_$i", "m_1", 10_000, "EUR", BigDecimal("0.21"), "NL", t0))
        service.refund(RefundCommand("p_7", "r_7", null, t0.plusSeconds(60)))

        val codes = dataSource.transaction { engine.balances(it) }.map { it.account.code }
        assertTrue(codes.count { it.startsWith("fee_revenue#") } > 1, "fee revenue spread over shards: $codes")
        assertEquals(listOf("merchant_payable:m_1"), codes.filter { it.startsWith("merchant_payable") })

        val shard = ChartOfAccounts.shardOf("p_7", 8)
        val refund = dataSource.transaction { engine.entries(it, paymentId = "p_7") }.first { it.kind == EntryKind.REFUND }
        assertTrue(refund.postings.any { it.account.code == "fee_revenue$shard" || it.account.code == "psp_clearing$shard" })
        assertEquals(emptyList(), dataSource.transaction { engine.verify(it) })
    }
}
