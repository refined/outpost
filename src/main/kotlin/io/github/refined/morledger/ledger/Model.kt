package io.github.refined.morledger.ledger

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

internal fun validatedPostings(entry: NewJournalEntry): List<Posting> {
    val lines = entry.postings.filter { it.amount != 0L }
    require(lines.size >= 2) { "a journal entry needs at least two non-zero postings" }
    require(lines.map { it.account.currency }.distinct().size == 1) { "a journal entry must use one currency" }
    require(lines.sumOf { it.amount } == 0L) { "unbalanced journal entry: postings sum to ${lines.sumOf { it.amount }}" }
    return lines
}
