package io.github.refined.morledger.ledger

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.db.transaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Below the API: which requests share a batch cannot be controlled over HTTP. */
class LedgerBatchCommitTest {
    private val dataSource = TestDatabase.dataSource
    private val ledger = LedgerWriter()
    private val clearing = Account("psp_clearing", "EUR", NormalSide.DEBIT)
    private val merchant = Account("merchant_payable:m_1", "EUR", NormalSide.CREDIT)

    private fun entry(id: String, amount: Long) =
        NewJournalEntry(EntryKind.CAPTURE, id, null, Instant.parse("2026-10-01T10:00:00Z"), listOf(Posting(clearing, amount), Posting(merchant, -amount)))

    @Test
    fun `a shared handler keeps concurrent writers and their typed results separate`() {
        TestDatabase.reset()
        val bothRunning = CyclicBarrier(2)
        val group = BatchGroup<Int, String> { conn, items, deltas ->
            bothRunning.await(5, TimeUnit.SECONDS)
            ledger.postAll(conn, items.map { entry("p_$it", 100) }, deltas)
            items.map { Result.success("payment_$it") }
        }
        LedgerBatchCommit(dataSource, ledger, maxSize = 1, maxWaitMs = 0).start().use { first ->
            LedgerBatchCommit(dataSource, ledger, maxSize = 1, maxWaitMs = 0).start().use { second ->
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val one = pool.submit<String> { runBlocking { first.submit(group, 1, BatchExecution.TOGETHER) } }
                    val two = pool.submit<String> { runBlocking { second.submit(group, 2, BatchExecution.TOGETHER) } }
                    assertEquals("payment_1", one.get(10, TimeUnit.SECONDS))
                    assertEquals("payment_2", two.get(10, TimeUnit.SECONDS))
                } finally {
                    pool.shutdownNow()
                }
            }
        }
        assertEquals(2, dataSource.transaction { LedgerTable.entries(it, limit = 1000) }.size)
        assertEquals(emptyList(), dataSource.transaction { LedgerTable.verify(it) })
    }

    @Test
    fun `a batch commits together, applies summed balances once and rolls back a failing item alone`() {
        TestDatabase.reset()
        val callSizes = CopyOnWriteArrayList<Int>()
        val group = BatchGroup<Int, Long> { conn, items, deltas ->
            callSizes += items.size
            items.map { i ->
                runCatching {
                    ledger.post(conn, entry("p_$i", 100), deltas)
                    if (i == 7) error("item 7 fails after writing its entry")
                    i.toLong()
                }
            }
        }
        LedgerBatchCommit(dataSource, ledger, maxSize = 100, maxWaitMs = 200).start().use { batchCommit ->
            val pool = Executors.newFixedThreadPool(20)
            val results = (1..20).map { i ->
                pool.submit<Long> {
                    runBlocking {
                        batchCommit.submit(group, i, BatchExecution.INDIVIDUAL)
                    }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

            assertFailsWith<Exception> { results[6].get() }
            assertEquals((1..20L).filter { it != 7L }, results.filterIndexed { i, _ -> i != 6 }.map { it.get() })
            assertTrue(batchCommit.batches < 20, "${batchCommit.items} items in ${batchCommit.batches} batches")
            assertEquals(List(20) { 1 }, callSizes)
        }

        val balances = dataSource.transaction { LedgerTable.balances(it) }.associate { it.account.code to it.signedSum }
        assertEquals(19 * 100L, balances["psp_clearing"])
        assertEquals(19L, dataSource.transaction { LedgerTable.entries(it, limit = 1000) }.size.toLong())
        assertEquals(emptyList(), dataSource.transaction { LedgerTable.verify(it) })
    }

    @Test
    fun `a group writes its items in one call per batch, and a failing group is retried item by item`() {
        TestDatabase.reset()
        val callSizes = CopyOnWriteArrayList<Int>()
        // Item 13 breaks whatever call it is in: the whole batch rolls back and every item is retried alone.
        val group = BatchGroup<Int, Int> { conn, items, deltas ->
            callSizes += items.size
            ledger.postAll(conn, items.map { entry("p_$it", 100) }, deltas)
            check(13 !in items) { "item 13 fails after writing" }
            items.map { Result.success(it) }
        }
        LedgerBatchCommit(dataSource, ledger, maxSize = 100, maxWaitMs = 200).start().use { batchCommit ->
            val pool = Executors.newFixedThreadPool(20)
            val results = (1..20).map { i -> pool.submit<Int> { runBlocking { batchCommit.submit(group, i, BatchExecution.TOGETHER) } } }
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

            assertFailsWith<Exception> { results[12].get() }
            assertEquals((1..20).filter { it != 13 }, results.filterIndexed { i, _ -> i != 12 }.map { it.get() })
            assertTrue(callSizes.max() > 1, "items were never grouped: $callSizes")
        }

        val balances = dataSource.transaction { LedgerTable.balances(it) }.associate { it.account.code to it.signedSum }
        assertEquals(19 * 100L, balances["psp_clearing"])
        assertEquals(19, dataSource.transaction { LedgerTable.entries(it, limit = 1000) }.size)
        assertEquals(emptyList(), dataSource.transaction { LedgerTable.verify(it) })
    }
}
