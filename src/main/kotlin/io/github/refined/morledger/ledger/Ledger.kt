package io.github.refined.morledger.ledger

import java.sql.Connection
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

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

/**
 * The double-entry ledger engine: the only code that touches the `ledger.*` tables.
 * It knows nothing about payments or merchants, so it could be replaced by TigerBeetle or Formance behind the same API.
 *
 * Every method takes the caller's [Connection], so the business record and its journal entry commit together.
 */
object Ledger {
    /** Posts a balanced, single-currency entry; zero-amount lines are dropped. Returns the entry id. */
    fun post(conn: Connection, entry: NewJournalEntry): Long {
        val lines = entry.postings.filter { it.amount != 0L }
        require(lines.size >= 2) { "a journal entry needs at least two non-zero postings" }
        require(lines.map { it.account.currency }.distinct().size == 1) { "a journal entry must use one currency" }
        require(lines.sumOf { it.amount } == 0L) { "unbalanced journal entry: postings sum to ${lines.sumOf { it.amount }}" }

        val accountIds = lines.map { it.account }.distinct().associateWith { accountId(conn, it) }
        val entryId = conn.prepareStatement(
            """
            INSERT INTO ledger.journal_entries (kind, payment_id, refund_id, effective_at)
            VALUES (?, ?, ?, ?) RETURNING id
            """.trimIndent(),
        ).use { st ->
            st.setString(1, entry.kind.name)
            st.setString(2, entry.paymentId)
            st.setString(3, entry.refundId)
            st.setObject(4, entry.effectiveAt.atOffset(ZoneOffset.UTC))
            st.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
        conn.prepareStatement("INSERT INTO ledger.postings (entry_id, account_id, amount) VALUES (?, ?, ?)").use { st ->
            for (line in lines) {
                st.setLong(1, entryId)
                st.setLong(2, accountIds.getValue(line.account))
                st.setLong(3, line.amount)
                st.addBatch()
            }
            st.executeBatch()
        }
        return entryId
    }

    /** Balances of every account, from entries effective at or before [asOf] (all entries when null). */
    fun balances(conn: Connection, asOf: Instant? = null, currency: String? = null): List<AccountBalance> =
        conn.prepareStatement(
            """
            SELECT a.code, a.currency, a.normal_side, SUM(p.amount)
            FROM ledger.postings p
            JOIN ledger.journal_entries e ON e.id = p.entry_id
            JOIN ledger.accounts a ON a.id = p.account_id
            WHERE (CAST(? AS TIMESTAMPTZ) IS NULL OR e.effective_at <= CAST(? AS TIMESTAMPTZ))
              AND (CAST(? AS TEXT) IS NULL OR a.currency = CAST(? AS TEXT))
            GROUP BY a.code, a.currency, a.normal_side
            ORDER BY a.currency, a.code
            """.trimIndent(),
        ).use { st ->
            val at = asOf?.atOffset(ZoneOffset.UTC)
            st.setObject(1, at, Types.TIMESTAMP_WITH_TIMEZONE)
            st.setObject(2, at, Types.TIMESTAMP_WITH_TIMEZONE)
            st.setString(3, currency)
            st.setString(4, currency)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val account = Account(rs.getString(1), rs.getString(2), NormalSide.valueOf(rs.getString(3)))
                        add(AccountBalance(account, rs.getLong(4)))
                    }
                }
            }
        }

    /** Most recent entries first, optionally for one payment. */
    fun entries(conn: Connection, paymentId: String? = null, limit: Int = 100): List<JournalEntry> {
        val rows = conn.prepareStatement(
            """
            SELECT e.id, e.kind, e.payment_id, e.refund_id, e.effective_at, e.recorded_at,
                   a.code, a.currency, a.normal_side, p.amount
            FROM (SELECT * FROM ledger.journal_entries
                  WHERE CAST(? AS TEXT) IS NULL OR payment_id = CAST(? AS TEXT)
                  ORDER BY id DESC LIMIT ?) e
            JOIN ledger.postings p ON p.entry_id = e.id
            JOIN ledger.accounts a ON a.id = p.account_id
            ORDER BY e.id DESC, p.id
            """.trimIndent(),
        ).use { st ->
            st.setString(1, paymentId)
            st.setString(2, paymentId)
            st.setInt(3, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val header = JournalEntry(
                            id = rs.getLong(1),
                            kind = EntryKind.valueOf(rs.getString(2)),
                            paymentId = rs.getString(3),
                            refundId = rs.getString(4),
                            effectiveAt = rs.getObject(5, OffsetDateTime::class.java).toInstant(),
                            recordedAt = rs.getObject(6, OffsetDateTime::class.java).toInstant(),
                            postings = emptyList(),
                        )
                        val account = Account(rs.getString(7), rs.getString(8), NormalSide.valueOf(rs.getString(9)))
                        add(header to Posting(account, rs.getLong(10)))
                    }
                }
            }
        }
        return rows.groupBy({ it.first.id }) { it }
            .map { (_, group) -> group.first().first.copy(postings = group.map { it.second }) }
    }

    private fun accountId(conn: Connection, account: Account): Long {
        conn.prepareStatement(
            "INSERT INTO ledger.accounts (code, currency, normal_side) VALUES (?, ?, ?) ON CONFLICT (code, currency) DO NOTHING",
        ).use { st ->
            st.setString(1, account.code)
            st.setString(2, account.currency)
            st.setString(3, account.normalSide.name)
            st.executeUpdate()
        }
        return conn.prepareStatement("SELECT id, normal_side FROM ledger.accounts WHERE code = ? AND currency = ?").use { st ->
            st.setString(1, account.code)
            st.setString(2, account.currency)
            st.executeQuery().use { rs ->
                rs.next()
                check(rs.getString(2) == account.normalSide.name) { "account ${account.code} exists with another normal side" }
                rs.getLong(1)
            }
        }
    }
}
