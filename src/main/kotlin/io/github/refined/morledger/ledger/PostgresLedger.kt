package io.github.refined.morledger.ledger

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** How current balances are kept. Switchable to compare the approaches (see docs/performance.md). */
enum class BalanceMode {
    /**
     * Each account stores a running `balance`, moved in the same transaction as the postings with an atomic
     * `UPDATE … SET balance = balance + x`. Reads are O(1); posting row-locks the touched accounts until commit.
     */
    STORED,

    /** Nothing is stored: a current balance is `SUM(postings)` at read time. Posting takes no locks; reads are O(postings). */
    COMPUTED,
}

/**
 * The ledger on Postgres (`ledger.*` tables). The database itself enforces that every entry balances (deferred
 * constraint trigger) and that history is append-only (triggers).
 *
 * With [BalanceMode.STORED], concurrent posts to the same account queue up on its row lock instead of losing updates.
 * Accounts are locked in id order (no deadlocks) and as the last statements of the transaction (short lock hold).
 * Historical (`asOf`) balances are always summed from postings.
 */
class PostgresLedger(private val balanceMode: BalanceMode = BalanceMode.STORED) : LedgerEngine {
    override val description = "postgres (balances: ${balanceMode.name.lowercase()})"

    override fun post(conn: Connection, entry: NewJournalEntry): Long {
        val lines = LedgerEngine.validated(entry)

        // Creation order is fixed too: two transactions creating the same new accounts must not deadlock.
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

    override fun balances(conn: Connection, asOf: Instant?, currency: String?): List<AccountBalance> = when {
        asOf != null -> summedBalances(conn, asOf, currency)
        balanceMode == BalanceMode.STORED -> storedBalances(conn, currency)
        else -> summedBalances(conn, null, currency)
    }

    /** In [BalanceMode.COMPUTED] nothing is stored, so there is nothing to reconcile. */
    override fun verify(conn: Connection): List<BalanceMismatch> {
        if (balanceMode == BalanceMode.COMPUTED) return emptyList()
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

    override fun entries(
        conn: Connection,
        paymentId: String?,
        accountCode: String?,
        limit: Int,
        beforeId: Long?,
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

    /** Σ postings per account, of entries effective at or before [asOf] (all entries when null). */
    private fun summedBalances(conn: Connection, asOf: Instant?, currency: String?): List<AccountBalance> =
        conn.prepareStatement(
            """
            SELECT a.code, a.currency, a.normal_side, SUM(p.amount)
            FROM ledger.postings p
            JOIN ledger.accounts a ON a.id = p.account_id
            ${if (asOf != null) "JOIN ledger.journal_entries e ON e.id = p.entry_id AND e.effective_at <= ?" else ""}
            WHERE CAST(? AS TEXT) IS NULL OR a.currency = CAST(? AS TEXT)
            GROUP BY a.code, a.currency, a.normal_side
            ORDER BY a.currency, a.code
            """.trimIndent(),
        ).use { st ->
            var i = 1
            if (asOf != null) st.setObject(i++, asOf.atOffset(ZoneOffset.UTC))
            st.setString(i++, currency)
            st.setString(i, currency)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(AccountBalance(rs.toAccount(), rs.getLong(4))) } }
        }

    /**
     * Account ids never change, so they are cached. Only ids of rows that were already there are cached: a row this
     * transaction just inserted might still roll back.
     */
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
}
