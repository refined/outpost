package io.github.refined.morledger.ledger

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.money.Split
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LedgerTest {
    private val dataSource = TestDatabase.dataSource
    private val t0 = Instant.parse("2026-10-01T10:00:00Z")

    @BeforeEach
    fun reset() = TestDatabase.reset()

    private fun capture(paymentId: String, split: Split, at: Instant = t0, currency: String = "EUR") = NewJournalEntry(
        kind = EntryKind.CAPTURE,
        paymentId = paymentId,
        refundId = null,
        effectiveAt = at,
        postings = ChartOfAccounts.capture(currency, "NL", "m_1", split),
    )

    @Test
    fun `posts an entry and derives balances on each account's normal side`() {
        dataSource.transaction { Ledger.post(it, capture("p_1", Split(tax = 2100, fee = 605, merchant = 9395))) }

        val balances = dataSource.transaction { Ledger.balances(it) }.associate { it.account.code to it.balance }
        assertEquals(
            mapOf("fee_revenue" to 605L, "merchant_payable:m_1" to 9395L, "psp_clearing" to 12100L, "tax_payable:NL" to 2100L),
            balances,
        )
    }

    @Test
    fun `zero lines are dropped and entries are listed with their postings`() {
        dataSource.transaction { Ledger.post(it, capture("p_1", Split(tax = 0, fee = 0, merchant = 500))) }

        val entries = dataSource.transaction { Ledger.entries(it, paymentId = "p_1") }
        assertEquals(1, entries.size)
        assertEquals(listOf(500L, -500L), entries.single().postings.map { it.amount })
    }

    @Test
    fun `balances as of a date ignore later entries`() {
        dataSource.transaction {
            Ledger.post(it, capture("p_1", Split(0, 0, 100), at = t0))
            Ledger.post(it, capture("p_2", Split(0, 0, 50), at = t0.plusSeconds(3600)))
        }

        val early = dataSource.transaction { Ledger.balances(it, asOf = t0.plusSeconds(60)) }
        assertEquals(100L, early.single { it.account.code == "psp_clearing" }.balance)
    }

    @Test
    fun `code rejects unbalanced and mixed-currency entries`() {
        val unbalanced = capture("p_1", Split(0, 0, 100)).let {
            it.copy(postings = it.postings + Posting(ChartOfAccounts.feeRevenue("EUR"), -1))
        }
        assertFailsWith<IllegalArgumentException> { dataSource.transaction { Ledger.post(it, unbalanced) } }

        val mixed = NewJournalEntry(
            EntryKind.CAPTURE, "p_2", null, t0,
            listOf(Posting(ChartOfAccounts.pspClearing("EUR"), 100), Posting(ChartOfAccounts.feeRevenue("USD"), -100)),
        )
        assertFailsWith<IllegalArgumentException> { dataSource.transaction { Ledger.post(it, mixed) } }
    }

    @Test
    fun `database rejects an unbalanced entry written around the code`() {
        val error = assertFailsWith<SQLException> {
            dataSource.transaction { conn ->
                val entryId = Ledger.post(conn, capture("p_1", Split(0, 0, 100)))
                conn.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO ledger.postings (entry_id, account_id, amount) SELECT $entryId, id, 1 FROM ledger.accounts LIMIT 1",
                    )
                }
            }
        }
        assertTrue(error.message!!.contains("unbalanced"), error.message)
        assertEquals(0, dataSource.transaction { Ledger.entries(it) }.size)
    }

    @Test
    fun `ledger is append-only`() {
        dataSource.transaction { Ledger.post(it, capture("p_1", Split(0, 0, 100))) }

        for (sql in listOf(
            "UPDATE ledger.postings SET amount = amount * 2",
            "DELETE FROM ledger.postings",
            "UPDATE ledger.journal_entries SET effective_at = now()",
            "DELETE FROM ledger.journal_entries",
        )) {
            val error = assertFailsWith<SQLException>(sql) {
                dataSource.transaction { conn -> conn.createStatement().use { it.executeUpdate(sql) } }
            }
            assertTrue(error.message!!.contains("append-only"), error.message)
        }
    }
}
