package io.github.refined.morledger.ledger

import io.github.refined.morledger.db.transaction
import kotlinx.coroutines.future.await
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

class LedgerBatchCommit(
    private val dataSource: DataSource,
    private val ledger: Ledger,
    private val maxSize: Int,
    private val maxWaitMs: Long,
) : AutoCloseable {
    private class Work<T>(val block: (Connection, MutableMap<Long, Long>) -> T) {
        val result = CompletableFuture<T>()

        @Suppress("UNCHECKED_CAST")
        fun complete(outcome: Result<Any?>) {
            outcome.fold({ result.complete(it as T) }, { result.completeExceptionally(it) })
        }
    }

    private val queue = LinkedBlockingQueue<Work<*>>()
    private val batchCount = AtomicLong()
    private val itemCount = AtomicLong()
    private val writer = Thread(::loop, "ledger-batch-commit").apply {
        isDaemon = true
        start()
    }

    val batches: Long get() = batchCount.get()
    val items: Long get() = itemCount.get()

    suspend fun <T> submit(block: (Connection, MutableMap<Long, Long>) -> T): T {
        val work = Work(block)
        queue.put(work)
        return work.result.await()
    }

    override fun close() {
        writer.interrupt()
        generateSequence { queue.poll() }.forEach { it.complete(Result.failure(IllegalStateException("shutting down"))) }
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            val first = try {
                queue.take()
            } catch (e: InterruptedException) {
                return
            }
            val batch = mutableListOf<Work<*>>(first)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs)
            while (batch.size < maxSize) {
                val next = try {
                    queue.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                batch += next ?: break
            }
            commit(batch)
        }
    }

    private fun commit(batch: List<Work<*>>) {
        batchCount.incrementAndGet()
        itemCount.addAndGet(batch.size.toLong())
        val outcomes = try {
            dataSource.transaction { conn ->
                val deltas = mutableMapOf<Long, Long>()
                val outcomes = batch.map { work ->
                    // A failed item rolls back to its savepoint, which also clears an aborted Postgres transaction.
                    val savepoint = conn.setSavepoint()
                    val own = mutableMapOf<Long, Long>()
                    runCatching { work.block(conn, own) }
                        .onSuccess {
                            conn.releaseSavepoint(savepoint)
                            own.forEach { (account, delta) -> deltas.merge(account, delta, Long::plus) }
                        }
                        .onFailure { conn.rollback(savepoint) }
                }
                ledger.applyDeltas(conn, deltas)
                outcomes
            }
        } catch (e: Exception) {
            // The commit itself failed: retry each item alone, so one bad item cannot fail its neighbours.
            ledger.forgetCachedAccounts()
            if (batch.size == 1) batch.single().complete(Result.failure(e)) else batch.forEach { commit(listOf(it)) }
            return
        }
        batch.zip(outcomes).forEach { (work, outcome) -> work.complete(outcome) }
    }
}
