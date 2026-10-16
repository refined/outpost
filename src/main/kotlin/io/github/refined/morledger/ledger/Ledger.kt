package io.github.refined.morledger.ledger

import java.sql.Connection
import java.time.Instant

enum class NormalSide { DEBIT, CREDIT }

data class Account(val code: String, val currency: String, val normalSide: NormalSide)

/** Signed minor units: debit > 0, credit < 0. */
data class Posting(val account: Account, val amount: Long)

enum class EntryKind { CAPTURE, REFUND }

data class NewJournalEntry(
    val kind: EntryKind,
    val paymentId: String,
    val refundId: String?,
    val effectiveAt: Instant,
    val postings: List<Posting>,
)

data class JournalEntry(
    val id: Long,
    val kind: EntryKind,
    val paymentId: String,
    val refundId: String?,
    val effectiveAt: Instant,
    val recordedAt: Instant,
    val postings: List<Posting>,
)

data class AccountBalance(val account: Account, val signedSum: Long) {
    val balance: Long get() = if (account.normalSide == NormalSide.DEBIT) signedSum else -signedSum
}

data class BalanceMismatch(val account: Account, val stored: Long, val fromPostings: Long)

class UnsupportedLedgerOperation(message: String) : RuntimeException(message)

data class LedgerLag(val pendingEntries: Long, val oldestPendingAgeMs: Long, val readLagMs: Long, val refreshedAt: Instant?) {
    companion object {
        val NONE = LedgerLag(0, 0, 0, null)
    }
}

interface LedgerEngine {
    val description: String

    fun post(conn: Connection, entry: NewJournalEntry): Long?

    fun balances(conn: Connection, asOf: Instant? = null, currency: String? = null): List<AccountBalance>

    fun verify(conn: Connection): List<BalanceMismatch>

    val refreshesBalances: Boolean get() = false

    fun refreshBalances(conn: Connection) = Unit

    fun lag(conn: Connection): LedgerLag = LedgerLag.NONE

    fun entries(
        conn: Connection,
        paymentId: String? = null,
        accountCode: String? = null,
        limit: Int = 100,
        beforeId: Long? = null,
    ): List<JournalEntry>

    companion object {
        fun validated(entry: NewJournalEntry): List<Posting> {
            val lines = entry.postings.filter { it.amount != 0L }
            require(lines.size >= 2) { "a journal entry needs at least two non-zero postings" }
            require(lines.map { it.account.currency }.distinct().size == 1) { "a journal entry must use one currency" }
            require(lines.sumOf { it.amount } == 0L) { "unbalanced journal entry: postings sum to ${lines.sumOf { it.amount }}" }
            return lines
        }
    }
}
