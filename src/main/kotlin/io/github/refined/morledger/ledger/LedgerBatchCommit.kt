package io.github.refined.morledger.ledger

import io.github.refined.morledger.db.transaction
import kotlinx.coroutines.future.await
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

class BatchGroup<I, T>(val write: (Connection, List<I>, MutableMap<Long, Long>) -> List<Result<T>>) {
    // Each connection belongs to one transaction; a handler may be shared by multiple writers.
    private val pending = ConcurrentHashMap<Connection, MutableList<LedgerBatchCommit.Work<I, T>>>()

    internal fun add(conn: Connection, work: LedgerBatchCommit.Work<I, T>) {
        pending.getOrPut(conn) { mutableListOf() }.add(work)
    }

    internal fun run(conn: Connection, deltas: MutableMap<Long, Long>) {
        val work = pending.getValue(conn)
        val results = write(conn, work.map { it.item }, deltas)
        check(results.size == work.size) { "a batch group must return one result per item" }
        for ((item, result) in work.zip(results)) item.outcome = result
    }

    internal fun clear(conn: Connection) {
        pending.remove(conn)
    }
}

enum class BatchExecution { INDIVIDUAL, TOGETHER }

class LedgerBatchCommit(
    private val dataSource: DataSource,
    private val ledger: LedgerWriter,
    private val maxSize: Int,
    private val maxWaitMs: Long,
) : AutoCloseable {
    internal class Work<I, T>(
        val group: BatchGroup<I, T>,
        val item: I,
        val execution: BatchExecution,
    ) {
        val result = CompletableFuture<T>()
        var outcome: Result<T>? = null

        fun collect(conn: Connection) {
            group.add(conn, this)
        }

        fun runAlone(conn: Connection, deltas: MutableMap<Long, Long>) {
            // A failed item rolls back to its savepoint, which also clears an aborted Postgres transaction.
            val savepoint = conn.setSavepoint()
            val own = mutableMapOf<Long, Long>()
            outcome = runCatching { group.write(conn, listOf(item), own).single().getOrThrow() }
                .onSuccess {
                    conn.releaseSavepoint(savepoint)
                    own.forEach { (account, delta) -> deltas.merge(account, delta, Long::plus) }
                }
                .onFailure { conn.rollback(savepoint) }
        }

        fun complete() {
            checkNotNull(outcome).fold({ result.complete(it) }, { result.completeExceptionally(it) })
        }
    }

    private val queue = LinkedBlockingQueue<Work<*, *>>()
    private val batchCount = AtomicLong()
    private val itemCount = AtomicLong()
    private val writer = Thread(::loop, "ledger-batch-commit").apply { isDaemon = true }

    val batches: Long get() = batchCount.get()
    val items: Long get() = itemCount.get()

    fun start(): LedgerBatchCommit = apply { writer.start() }

    suspend fun <I, T> submit(group: BatchGroup<I, T>, item: I, execution: BatchExecution): T {
        check(writer.isAlive) { "the batch writer is not running: call start() first" }
        val work = Work(group, item, execution)
        queue.put(work)
        return work.result.await()
    }

    override fun close() {
        writer.interrupt()
        generateSequence { queue.poll() }.forEach { it.result.completeExceptionally(IllegalStateException("shutting down")) }
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
        try {
            dataSource.transaction { conn ->
                val deltas = mutableMapOf<Long, Long>()
                val groups = linkedSetOf<BatchGroup<*, *>>()
                try {
                    for (work in batch) {
                        work.outcome = null
                        if (work.execution == BatchExecution.TOGETHER) {
                            groups.add(work.group)
                            work.collect(conn)
                        }
                    }
                    // Groups run before individual items; requests in one batch were concurrent, so any order is valid.
                    for (group in groups) group.run(conn, deltas)
                    for (work in batch) {
                        if (work.execution == BatchExecution.INDIVIDUAL) work.runAlone(conn, deltas)
                    }
                    LedgerTable.applyDeltas(conn, deltas)
                } finally {
                    for (group in groups) group.clear(conn)
                }
            }
        } catch (e: Exception) {
            // The commit itself failed: retry each item alone, so one bad item cannot fail its neighbours.
            ledger.forgetCachedAccounts()
            if (batch.size == 1) batch.single().result.completeExceptionally(e) else batch.forEach { commit(listOf(it)) }
            return
        }
        for (work in batch) work.complete()
    }
}
