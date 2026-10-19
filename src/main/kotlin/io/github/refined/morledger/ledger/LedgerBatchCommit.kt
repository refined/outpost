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

enum class BatchExecution { INDIVIDUAL, TOGETHER }

class LedgerBatchCommit(
    private val dataSource: DataSource,
    private val ledger: LedgerWriter,
    private val maxSize: Int,
    private val maxWaitMs: Long,
) : AutoCloseable {
    private class Work<I, T>(
        val group: BatchGroup<I, T>,
        val item: I,
        val execution: BatchExecution,
    ) {
        val result = CompletableFuture<T>()

        // Each outcome comes from this work's typed batch group.
        @Suppress("UNCHECKED_CAST")
        fun complete(outcome: Result<Any?>) {
            outcome.fold({ result.complete(it as T) }, { result.completeExceptionally(it) })
        }
    }

    private val queue = LinkedBlockingQueue<Work<*, *>>()
    private val batchCount = AtomicLong()
    private val itemCount = AtomicLong()
    private val writer = Thread(::loop, "ledger-batch-commit").apply {
        isDaemon = true
        start()
    }

    val batches: Long get() = batchCount.get()
    val items: Long get() = itemCount.get()

    suspend fun <T> submit(block: (Connection, MutableMap<Long, Long>) -> T): T {
        val group = BatchGroup<Unit, T> { conn, _, deltas -> listOf(Result.success(block(conn, deltas))) }
        return submit(group, Unit, BatchExecution.INDIVIDUAL)
    }

    suspend fun <I, T> submit(group: BatchGroup<I, T>, item: I, execution: BatchExecution): T {
        val work = Work(group, item, execution)
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

    private fun commit(batch: List<Work<*, *>>) {
        batchCount.incrementAndGet()
        itemCount.addAndGet(batch.size.toLong())
        val outcomes = try {
            dataSource.transaction { conn ->
                val deltas = mutableMapOf<Long, Long>()
                val outcomes = mutableMapOf<Work<*, *>, Result<Any?>>()
                val groups = linkedMapOf<BatchGroup<*, *>, MutableList<Work<*, *>>>()
                for (work in batch) {
                    if (work.execution == BatchExecution.TOGETHER) {
                        groups.getOrPut(work.group) { mutableListOf() }.add(work)
                    }
                }
                // Groups run before individual items; requests in one batch were concurrent, so any order is valid.
                for ((group, work) in groups) {
                    val results = runGroup(conn, group, work, deltas)
                    for ((item, result) in work.zip(results)) {
                        outcomes[item] = result
                    }
                }
                for (work in batch) {
                    if (work.execution == BatchExecution.INDIVIDUAL) outcomes[work] = runAlone(conn, work, deltas)
                }
                LedgerTable.applyDeltas(conn, deltas)
                outcomes
            }
        } catch (e: Exception) {
            // The commit itself failed: retry each item alone, so one bad item cannot fail its neighbours.
            ledger.forgetCachedAccounts()
            if (batch.size == 1) batch.single().complete(Result.failure(e)) else batch.forEach { commit(listOf(it)) }
            return
        }
        for (work in batch) work.complete(outcomes.getValue(work))
    }

    private fun runGroup(
        conn: Connection,
        group: BatchGroup<*, *>,
        work: List<Work<*, *>>,
        deltas: MutableMap<Long, Long>,
    ): List<Result<Any?>> {
        @Suppress("UNCHECKED_CAST")
        val typedGroup = group as BatchGroup<Any?, Any?>
        val results = typedGroup.write(conn, work.map { it.item }, deltas)
        check(results.size == work.size) { "a batch group must return one result per item" }
        return results
    }

    private fun runAlone(conn: Connection, work: Work<*, *>, deltas: MutableMap<Long, Long>): Result<Any?> {
        // A failed item rolls back to its savepoint, which also clears an aborted Postgres transaction.
        val savepoint = conn.setSavepoint()
        val own = mutableMapOf<Long, Long>()
        return runCatching { runGroup(conn, work.group, listOf(work), own).single().getOrThrow() }
            .onSuccess {
                conn.releaseSavepoint(savepoint)
                own.forEach { (account, delta) -> deltas.merge(account, delta, Long::plus) }
            }
            .onFailure { conn.rollback(savepoint) }
    }
}
