package io.github.refined.morledger.ledger

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.db.transaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Below the API: which requests share a batch cannot be controlled over HTTP. */
class LedgerBatchCommitTest {
    private val dataSource = TestDatabase.dataSource
    private val ledger = Ledger()
    private val clearing = Account("psp_clearing", "EUR", NormalSide.DEBIT)
    private val merchant = Account("merchant_payable:m_1", "EUR", NormalSide.CREDIT)

    private fun entry(id: String, amount: Long) =
        NewJournalEntry(EntryKind.CAPTURE, id, null, Instant.parse("2026-10-01T10:00:00Z"), listOf(Posting(clearing, amount), Posting(merchant, -amount)))

    @Test
    fun `a batch commits together, applies summed balances once and rolls back a failing item alone`() {
        TestDatabase.reset()
        LedgerBatchCommit(dataSource, ledger, maxSize = 100, maxWaitMs = 200).use { batchCommit ->
            val pool = Executors.newFixedThreadPool(20)
            val results = (1..20).map { i ->
                pool.submit<Long> {
                    runBlocking {
                        batchCommit.submit { conn, deltas ->
                            ledger.post(conn, entry("p_$i", 100), deltas)
                            if (i == 7) error("item 7 fails after writing its entry")
                            i.toLong()
                        }
                    }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

            assertFailsWith<Exception> { results[6].get() }
            assertEquals((1..20L).filter { it != 7L }, results.filterIndexed { i, _ -> i != 6 }.map { it.get() })
            assertTrue(batchCommit.batches < 20, "${batchCommit.items} items in ${batchCommit.batches} batches")
        }

        val balances = dataSource.transaction { ledger.balances(it) }.associate { it.account.code to it.signedSum }
        assertEquals(19 * 100L, balances["psp_clearing"])
        assertEquals(19L, dataSource.transaction { ledger.entries(it, limit = 1000) }.size.toLong())
        assertEquals(emptyList(), dataSource.transaction { ledger.verify(it) })
    }
}
