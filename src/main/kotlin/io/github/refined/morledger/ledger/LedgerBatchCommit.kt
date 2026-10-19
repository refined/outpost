package io.github.refined.morledger.ledger

import io.github.refined.morledger.db.transaction
import kotlinx.coroutines.future.await
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

fun interface BatchGroup<I, R> {
    fun write(conn: Connection, items: List<I>, deltas: MutableMap<Long, Long>): List<Result<R>>
}

class LedgerBatchCommit(
    private val dataSource: DataSource,
    private val ledger: LedgerWriter,
    private val maxSize: Int,
    private val maxWaitMs: Long,
) : AutoCloseable {
    private sealed class Work<T> {
        val result = CompletableFuture<T>()

        @Suppress("UNCHECKED_CAST")
        fun complete(outcome: Result<Any?>) {
            outcome.fold({ result.complete(it as T) }, { result.completeExceptionally(it) })
        }
    }

    private class Single<T>(val block: (Connection, MutableMap<Long, Long>) -> T) : Work<T>()

    private class Grouped<I, T>(val group: BatchGroup<I, T>, val item: I) : Work<T>()

    private val queue = LinkedBlockingQueue<Work<*>>()
    private val batchCount = AtomicLong()
    private val itemCount = AtomicLong()
    private val writer = Thread(::loop, "ledger-batch-commit").apply {
        isDaemon = true
        start()
    }

    val batches: Long get() = batchCount.get()
    val items: Long get() = itemCount.get()

    suspend fun <T> submit(block: (Connection, MutableMap<Long, Long>) -> T): T = enqueue(Single(block))

    suspend fun <I, T> submit(group: BatchGroup<I, T>, item: I): T = enqueue(Grouped(group, item))

    private suspend fun <T> enqueue(work: Work<T>): T {
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
            val batch = mutableListOf(first)
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
                val outcomes = arrayOfNulls<Result<Any?>>(batch.size)
                // Groups run before single items; requests in one batch were concurrent, so any order is a valid one.
                batch.indices.filter { batch[it] is Grouped<*, *> }
                    .groupBy { (batch[it] as Grouped<*, *>).group }
                    .forEach { (group, indices) ->
                        @Suppress("UNCHECKED_CAST")
                        val results = (group as BatchGroup<Any?, Any?>).write(conn, indices.map { (batch[it] as Grouped<*, *>).item }, deltas)
                        check(results.size == indices.size) { "a batch group must return one result per item" }
                        indices.zip(results).forEach { (i, result) -> outcomes[i] = result }
                    }
                batch.forEachIndexed { i, work ->
                    if (work is Single<*>) outcomes[i] = runAlone(conn, work, deltas)
                }
                LedgerTable.applyDeltas(conn, deltas)
                outcomes.map { it!! }
            }
        } catch (e: Exception) {
            // The commit itself failed: retry each item alone, so one bad item cannot fail its neighbours.
            ledger.forgetCachedAccounts()
            if (batch.size == 1) batch.single().complete(Result.failure(e)) else batch.forEach { commit(listOf(it)) }
            return
        }
        batch.zip(outcomes).forEach { (work, outcome) -> work.complete(outcome) }
    }

    private fun runAlone(conn: Connection, work: Single<*>, deltas: MutableMap<Long, Long>): Result<Any?> {
        // A failed item rolls back to its savepoint, which also clears an aborted Postgres transaction.
        val savepoint = conn.setSavepoint()
        val own = mutableMapOf<Long, Long>()
        return runCatching { work.block(conn, own) }
            .onSuccess {
                conn.releaseSavepoint(savepoint)
                own.forEach { (account, delta) -> deltas.merge(account, delta, Long::plus) }
            }
            .onFailure { conn.rollback(savepoint) }
    }
}
