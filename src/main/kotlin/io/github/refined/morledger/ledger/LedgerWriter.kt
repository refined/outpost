package io.github.refined.morledger.ledger

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

class LedgerWriter {
    // Cache only existing rows: an account inserted by this transaction may still roll back.
    private val accountIds = ConcurrentHashMap<Account, Long>()

    fun post(conn: Connection, entry: NewJournalEntry, deltas: MutableMap<Long, Long>? = null): Long {
        val lines = validatedPostings(entry)
        // Fixed account order prevents deadlocks between concurrent writers.
        val ids = lines.map { it.account }.distinct().sortedBy { it.code + "|" + it.currency }
            .associateWith { accountId(conn, it) }
        val entryId = LedgerTable.insertEntry(conn, entry)
        LedgerTable.insertPostings(conn, entryId, lines.map { ids.getValue(it.account) to it.amount })
        val entryDeltas = deltas ?: mutableMapOf()
        for (line in lines) entryDeltas.merge(ids.getValue(line.account), line.amount, Long::plus)
        if (deltas == null) LedgerTable.applyDeltas(conn, entryDeltas)
        return entryId
    }

    // A batch may cache accounts created by an earlier item; a failed commit invalidates those ids.
    fun forgetCachedAccounts() = accountIds.clear()

    private fun accountId(conn: Connection, account: Account): Long {
        accountIds[account]?.let { return it }
        findAccount(conn, account)?.let { id ->
            accountIds[account] = id
            return id
        }
        LedgerTable.insertAccount(conn, account)
        return checkNotNull(findAccount(conn, account)) { "inserted account ${account.code} not found" }
    }

    private fun findAccount(conn: Connection, account: Account): Long? {
        val existing = LedgerTable.findAccount(conn, account.code, account.currency) ?: return null
        check(existing.normalSide == account.normalSide) { "account ${account.code} exists with another normal side" }
        return existing.id
    }
}
