package io.github.refined.morledger.ledger

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

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

data class LedgerLag(val pendingEntries: Long, val readLagMs: Long, val refreshedAt: Instant?) {
    companion object {
        val NONE = LedgerLag(0, 0, null)
    }
}

enum class BalanceMode { STORED, BATCHED }

class Ledger(private val balanceMode: BalanceMode = BalanceMode.STORED) {
    val description = "postgres (balances: ${balanceMode.name.lowercase()})"

    val refreshesBalances = balanceMode == BalanceMode.BATCHED

    fun post(conn: Connection, entry: NewJournalEntry): Long {
        val lines = validated(entry)

        // Accounts are created and locked in a fixed order, so concurrent postings cannot deadlock.
        val accountIds = lines.map { it.account }.distinct().sortedBy { it.code + "|" + it.currency }
            .associateWith { accountId(conn, it) }
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

        if (balanceMode == BalanceMode.STORED) {
            val deltas = lines.groupBy { accountIds.getValue(it.account) }.mapValues { (_, l) -> l.sumOf { it.amount } }
            conn.prepareStatement("UPDATE ledger.accounts SET balance = balance + ? WHERE id = ?").use { st ->
                for ((accountId, delta) in deltas.toSortedMap()) {
                    st.setLong(1, delta)
                    st.setLong(2, accountId)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
        return entryId
    }

    fun balances(conn: Connection, asOf: Instant? = null, currency: String? = null): List<AccountBalance> = when {
        asOf != null -> summedBalances(conn, asOf, currency)
        balanceMode == BalanceMode.STORED -> storedBalances(conn, currency)
        else -> snapshotBalances(conn, currency)
    }

    // Boundary = pg_snapshot_xmin: every xid below it has finished, so no later commit can land behind the snapshot.
    fun refreshBalances(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT boundary FROM ledger.snapshot_state FOR UPDATE").close()
            st.executeUpdate(
                """
                WITH b AS (SELECT s.boundary AS old, pg_snapshot_xmin(pg_current_snapshot()) AS new FROM ledger.snapshot_state s),
                d AS (SELECT p.account_id, SUM(p.amount) AS delta
                      FROM ledger.postings p JOIN ledger.journal_entries e ON e.id = p.entry_id, b
                      WHERE e.txid >= b.old AND e.txid < b.new
                      GROUP BY p.account_id),
                up AS (INSERT INTO ledger.balance_snapshots (account_id, balance) SELECT account_id, delta FROM d
                       ON CONFLICT (account_id) DO UPDATE SET balance = ledger.balance_snapshots.balance + EXCLUDED.balance)
                UPDATE ledger.snapshot_state SET boundary = (SELECT new FROM b), refreshed_at = now()
                """.trimIndent(),
            )
        }
    }

    fun lag(conn: Connection): LedgerLag {
        if (!refreshesBalances) return LedgerLag.NONE
        return conn.prepareStatement(
            """
            SELECT count(e.id), COALESCE(EXTRACT(EPOCH FROM now() - min(e.recorded_at)) * 1000, 0)::bigint,
                   (SELECT refreshed_at FROM ledger.snapshot_state)
            FROM ledger.journal_entries e WHERE e.txid >= (SELECT boundary FROM ledger.snapshot_state)
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.next()
                LedgerLag(
                    pendingEntries = rs.getLong(1),
                    readLagMs = rs.getLong(2),
                    refreshedAt = rs.getObject(3, OffsetDateTime::class.java).toInstant(),
                )
            }
        }
    }

    fun verify(conn: Connection): List<BalanceMismatch> {
        if (refreshesBalances) return verifySnapshot(conn)
        return conn.prepareStatement(
            """
            SELECT a.code, a.currency, a.normal_side, a.balance, COALESCE(SUM(p.amount), 0)
            FROM ledger.accounts a
            LEFT JOIN ledger.postings p ON p.account_id = a.id
            GROUP BY a.id
            HAVING a.balance <> COALESCE(SUM(p.amount), 0)
            ORDER BY a.currency, a.code
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(BalanceMismatch(rs.toAccount(), rs.getLong(4), rs.getLong(5))) }
            }
        }
    }

    fun entries(
        conn: Connection,
        paymentId: String? = null,
        accountCode: String? = null,
        limit: Int = 100,
        beforeId: Long? = null,
    ): List<JournalEntry> {
        val rows = conn.prepareStatement(
            """
            SELECT e.id, e.kind, e.payment_id, e.refund_id, e.effective_at, e.recorded_at,
                   a.code, a.currency, a.normal_side, p.amount
            FROM (SELECT * FROM ledger.journal_entries je
                  WHERE (CAST(? AS TEXT) IS NULL OR je.payment_id = CAST(? AS TEXT))
                    AND (CAST(? AS BIGINT) IS NULL OR je.id < CAST(? AS BIGINT))
                    AND (CAST(? AS TEXT) IS NULL OR EXISTS (
                         SELECT 1 FROM ledger.postings pp JOIN ledger.accounts aa ON aa.id = pp.account_id
                         WHERE pp.entry_id = je.id AND aa.code = CAST(? AS TEXT)))
                  ORDER BY je.id DESC LIMIT ?) e
            JOIN ledger.postings p ON p.entry_id = e.id
            JOIN ledger.accounts a ON a.id = p.account_id
            ORDER BY e.id DESC, p.id
            """.trimIndent(),
        ).use { st ->
            st.setString(1, paymentId)
            st.setString(2, paymentId)
            st.setObject(3, beforeId)
            st.setObject(4, beforeId)
            st.setString(5, accountCode)
            st.setString(6, accountCode)
            st.setInt(7, limit)
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

    private fun verifySnapshot(conn: Connection): List<BalanceMismatch> =
        conn.prepareStatement(
            """
            SELECT a.code, a.currency, a.normal_side, COALESCE(s.balance, 0), COALESCE(t.total, 0)
            FROM ledger.accounts a
            LEFT JOIN ledger.balance_snapshots s ON s.account_id = a.id
            LEFT JOIN (SELECT p.account_id, SUM(p.amount) AS total
                       FROM ledger.postings p JOIN ledger.journal_entries e ON e.id = p.entry_id
                       WHERE e.txid < (SELECT boundary FROM ledger.snapshot_state)
                       GROUP BY p.account_id) t ON t.account_id = a.id
            WHERE COALESCE(s.balance, 0) <> COALESCE(t.total, 0)
            ORDER BY a.currency, a.code
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(BalanceMismatch(rs.toAccount(), rs.getLong(4), rs.getLong(5))) }
            }
        }

    private fun snapshotBalances(conn: Connection, currency: String?): List<AccountBalance> =
        conn.prepareStatement(
            """
            SELECT a.code, a.currency, a.normal_side, COALESCE(s.balance, 0)
            FROM ledger.accounts a
            LEFT JOIN ledger.balance_snapshots s ON s.account_id = a.id
            WHERE CAST(? AS TEXT) IS NULL OR a.currency = CAST(? AS TEXT)
            ORDER BY a.currency, a.code
            """.trimIndent(),
        ).use { st ->
            st.setString(1, currency)
            st.setString(2, currency)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(AccountBalance(rs.toAccount(), rs.getLong(4))) } }
        }

    private fun storedBalances(conn: Connection, currency: String?): List<AccountBalance> =
        conn.prepareStatement(
            """
            SELECT code, currency, normal_side, balance FROM ledger.accounts
            WHERE CAST(? AS TEXT) IS NULL OR currency = CAST(? AS TEXT)
            ORDER BY currency, code
            """.trimIndent(),
        ).use { st ->
            st.setString(1, currency)
            st.setString(2, currency)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(AccountBalance(rs.toAccount(), rs.getLong(4))) } }
        }

    private fun summedBalances(conn: Connection, asOf: Instant, currency: String?): List<AccountBalance> =
        conn.prepareStatement(
            """
            SELECT a.code, a.currency, a.normal_side, SUM(p.amount)
            FROM ledger.postings p
            JOIN ledger.accounts a ON a.id = p.account_id
            JOIN ledger.journal_entries e ON e.id = p.entry_id AND e.effective_at <= ?
            WHERE CAST(? AS TEXT) IS NULL OR a.currency = CAST(? AS TEXT)
            GROUP BY a.code, a.currency, a.normal_side
            ORDER BY a.currency, a.code
            """.trimIndent(),
        ).use { st ->
            st.setObject(1, asOf.atOffset(ZoneOffset.UTC))
            st.setString(2, currency)
            st.setString(3, currency)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(AccountBalance(rs.toAccount(), rs.getLong(4))) } }
        }

    /** Only ids of rows that were already there are cached: a row this transaction just inserted might still roll back. */
    private val accountIds = ConcurrentHashMap<Account, Long>()

    private fun accountId(conn: Connection, account: Account): Long {
        accountIds[account]?.let { return it }
        findAccount(conn, account)?.let { id -> accountIds[account] = id; return id }
        conn.prepareStatement(
            "INSERT INTO ledger.accounts (code, currency, normal_side) VALUES (?, ?, ?) ON CONFLICT (code, currency) DO NOTHING",
        ).use { st ->
            st.setString(1, account.code)
            st.setString(2, account.currency)
            st.setString(3, account.normalSide.name)
            st.executeUpdate()
        }
        return findAccount(conn, account)!!
    }

    private fun findAccount(conn: Connection, account: Account): Long? =
        conn.prepareStatement("SELECT id, normal_side FROM ledger.accounts WHERE code = ? AND currency = ?").use { st ->
            st.setString(1, account.code)
            st.setString(2, account.currency)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                check(rs.getString(2) == account.normalSide.name) { "account ${account.code} exists with another normal side" }
                rs.getLong(1)
            }
        }

    private fun ResultSet.toAccount() = Account(getString(1), getString(2), NormalSide.valueOf(getString(3)))

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
