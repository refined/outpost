package io.github.refined.morledger.ledger

import io.github.refined.morledger.db.MAX_ROWS_PER_STATEMENT
import io.github.refined.morledger.db.batch
import io.github.refined.morledger.db.instant
import io.github.refined.morledger.db.query
import io.github.refined.morledger.db.sql
import io.github.refined.morledger.db.update
import io.github.refined.morledger.db.where
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

internal object LedgerTable {
    /** Entry ids in the order of [entries]; each entry must have its own (kind, paymentId, refundId). */
    fun insertEntries(conn: Connection, entries: List<NewJournalEntry>): List<Long> {
        val keys = entries.map { Triple(it.kind.name, it.paymentId, it.refundId) }
        require(keys.distinct().size == keys.size) { "entries in one insert must have distinct keys" }
        // RETURNING has no guaranteed row order, so ids are matched back by key.
        val ids = entries.chunked(MAX_ROWS_PER_STATEMENT).flatMap { chunk ->
            conn.query(sql {
                """
                INSERT INTO ledger.journal_entries (kind, payment_id, refund_id, effective_at)
                VALUES ${chunk.joinToString(", ") { "(${param(it.kind.name)}, ${param(it.paymentId)}, ${param(it.refundId)}, ${param(it.effectiveAt)})" }}
                RETURNING id, kind, payment_id, refund_id
                """
            }) { Triple(it.getString("kind"), it.getString("payment_id"), it.getString("refund_id")) to it.getLong("id") }
        }.toMap()
        return keys.map { ids.getValue(it) }
    }

    fun insertPostings(conn: Connection, postings: List<NewPosting>) {
        postings.chunked(MAX_ROWS_PER_STATEMENT).forEach { chunk ->
            conn.update(sql {
                """
                INSERT INTO ledger.postings (entry_id, account_id, amount)
                VALUES ${chunk.joinToString(", ") { "(${param(it.entryId)}, ${param(it.accountId)}, ${param(it.amount)})" }}
                """
            })
        }
    }

    fun applyDeltas(conn: Connection, deltas: Map<Long, Long>) {
        // Balance rows must be locked in the same order by every writer.
        conn.batch(deltas.toSortedMap().entries.filter { it.value != 0L }) { (accountId, delta) ->
            sql {
                """
                UPDATE ledger.accounts
                SET balance = balance + ${param(delta)}
                WHERE id = ${param(accountId)}
                """
            }
        }
    }

    fun insertAccount(conn: Connection, account: Account) {
        conn.update(sql {
            """
            INSERT INTO ledger.accounts (code, currency, normal_side)
            VALUES (${param(account.code)}, ${param(account.currency)}, ${param(account.normalSide.name)})
            ON CONFLICT (code, currency) DO NOTHING
            """
        })
    }

    fun findAccount(conn: Connection, code: String, currency: String): StoredAccount? = conn.query(sql {
        """
        SELECT id, normal_side
        FROM ledger.accounts
        WHERE code = ${param(code)} AND currency = ${param(currency)}
        """
    }) { StoredAccount(it.getLong("id"), NormalSide.valueOf(it.getString("normal_side"))) }.singleOrNull()

    fun balances(conn: Connection, asOf: Instant? = null, currency: String? = null): List<AccountBalance> =
        if (asOf == null) storedBalances(conn, currency) else summedBalances(conn, asOf, currency)

    fun verify(conn: Connection): List<BalanceMismatch> = conn.query(sql {
        """
        SELECT a.code, a.currency, a.normal_side, a.balance, COALESCE(SUM(p.amount), 0) AS from_postings
        FROM ledger.accounts a
        LEFT JOIN ledger.postings p ON p.account_id = a.id
        GROUP BY a.id
        HAVING a.balance <> COALESCE(SUM(p.amount), 0)
        ORDER BY a.currency, a.code
        """
    }) { BalanceMismatch(it.toAccount(), it.getLong("balance"), it.getLong("from_postings")) }

    fun entries(
        conn: Connection,
        paymentId: String? = null,
        accountCode: String? = null,
        limit: Int = 100,
        beforeId: Long? = null,
    ): List<JournalEntry> {
        val rows = conn.query(sql {
            """
            SELECT e.id, e.kind, e.payment_id, e.refund_id, e.effective_at, e.recorded_at,
                   a.code, a.currency, a.normal_side, p.amount
            FROM (
                SELECT *
                FROM ledger.journal_entries je
                WHERE ${where {
                    if (paymentId != null) add("je.payment_id = ${param(paymentId)}")
                    if (beforeId != null) add("je.id < ${param(beforeId)}")
                    if (accountCode != null) add("""
                        EXISTS (
                            SELECT 1
                            FROM ledger.postings pp
                            JOIN ledger.accounts aa ON aa.id = pp.account_id
                            WHERE pp.entry_id = je.id AND aa.code = ${param(accountCode)}
                        )
                    """.trimIndent())
                }}
                ORDER BY je.id DESC
                LIMIT ${param(limit)}
            ) e
            JOIN ledger.postings p ON p.entry_id = e.id
            JOIN ledger.accounts a ON a.id = p.account_id
            ORDER BY e.id DESC, p.id
            """
        }) { row ->
            JournalEntry(
                id = row.getLong("id"),
                kind = EntryKind.valueOf(row.getString("kind")),
                paymentId = row.getString("payment_id"),
                refundId = row.getString("refund_id"),
                effectiveAt = row.instant("effective_at"),
                recordedAt = row.instant("recorded_at"),
                postings = emptyList(),
            ) to Posting(row.toAccount(), row.getLong("amount"))
        }
        return rows.groupBy { it.first.id }
            .map { (_, group) -> group.first().first.copy(postings = group.map { it.second }) }
    }

    private fun storedBalances(conn: Connection, currency: String?): List<AccountBalance> = conn.query(sql {
        """
        SELECT code, currency, normal_side, balance
        FROM ledger.accounts
        WHERE ${where {
            if (currency != null) add("currency = ${param(currency)}")
        }}
        ORDER BY currency, code
        """
    }) { AccountBalance(it.toAccount(), it.getLong("balance")) }

    private fun summedBalances(conn: Connection, asOf: Instant, currency: String?): List<AccountBalance> = conn.query(sql {
        """
        SELECT a.code, a.currency, a.normal_side, SUM(p.amount) AS balance
        FROM ledger.postings p
        JOIN ledger.accounts a ON a.id = p.account_id
        JOIN ledger.journal_entries e ON e.id = p.entry_id AND e.effective_at <= ${param(asOf)}
        WHERE ${where {
            if (currency != null) add("a.currency = ${param(currency)}")
        }}
        GROUP BY a.code, a.currency, a.normal_side
        ORDER BY a.currency, a.code
        """
    }) { AccountBalance(it.toAccount(), it.getLong("balance")) }

    private fun ResultSet.toAccount() = Account(getString("code"), getString("currency"), NormalSide.valueOf(getString("normal_side")))
}

internal data class StoredAccount(val id: Long, val normalSide: NormalSide)

internal data class NewPosting(val entryId: Long, val accountId: Long, val amount: Long)
