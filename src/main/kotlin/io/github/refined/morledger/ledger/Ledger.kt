package io.github.refined.morledger.ledger

import java.sql.Connection
import java.time.Instant

/** The side on which an account's balance is positive: assets on debit, liabilities and revenue on credit. */
enum class NormalSide { DEBIT, CREDIT }

data class Account(val code: String, val currency: String, val normalSide: NormalSide)

/** One line of a journal entry. Signed minor units: debit > 0, credit < 0. */
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

/** Signed sum of an account's postings (debit > 0). [balance] flips it to the account's normal side. */
data class AccountBalance(val account: Account, val signedSum: Long) {
    val balance: Long get() = if (account.normalSide == NormalSide.DEBIT) signedSum else -signedSum
}

/** An account whose stored balance differs from the sum of its postings. */
data class BalanceMismatch(val account: Account, val stored: Long, val fromPostings: Long)

/** Thrown for a query an engine cannot answer, e.g. historical balances on an engine without history. */
class UnsupportedLedgerOperation(message: String) : RuntimeException(message)

/**
 * The double-entry ledger engine. It knows nothing about payments or merchants: it posts balanced entries and reports
 * account balances. Implementations: [PostgresLedger] (default) and [TigerBeetleLedger].
 *
 * Every call takes the caller's [Connection], so a business record and its journal entry commit together.
 */
interface LedgerEngine {
    val description: String

    /** Validates and posts a balanced, single-currency entry; zero lines are dropped. Returns the entry id if the engine has one. */
    fun post(conn: Connection, entry: NewJournalEntry): Long?

    /** Current balances, or balances as of [asOf] (event time) when given. */
    fun balances(conn: Connection, asOf: Instant? = null, currency: String? = null): List<AccountBalance>

    /** Reconciliation: accounts whose stored balance is not the sum of their postings. Empty when the ledger is sound. */
    fun verify(conn: Connection): List<BalanceMismatch>

    /** Most recent entries first, optionally for one payment or one account code, with keyset pagination on the id. */
    fun entries(
        conn: Connection,
        paymentId: String? = null,
        accountCode: String? = null,
        limit: Int = 100,
        beforeId: Long? = null,
    ): List<JournalEntry>

    companion object {
        /** The checks every engine applies before posting. Returns the non-zero lines. */
        fun validated(entry: NewJournalEntry): List<Posting> {
            val lines = entry.postings.filter { it.amount != 0L }
            require(lines.size >= 2) { "a journal entry needs at least two non-zero postings" }
            require(lines.map { it.account.currency }.distinct().size == 1) { "a journal entry must use one currency" }
            require(lines.sumOf { it.amount } == 0L) { "unbalanced journal entry: postings sum to ${lines.sumOf { it.amount }}" }
            return lines
        }
    }
}
