package io.github.refined.morledger.ledger

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.db.transaction
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/** Engine level: an open transaction spanning a refresh cannot be set up through the API. */
class SnapshotRefreshTest {
    private val dataSource = TestDatabase.dataSource
    private val t0 = Instant.parse("2026-10-01T10:00:00Z")
    private val batched = Ledger(BalanceMode.BATCHED)

    private fun balances() = dataSource.transaction { batched.balances(it) }.associate { it.account.code to it.signedSum }

    private fun refresh() = dataSource.transaction { batched.refreshBalances(it) }

    @Test
    fun `an entry still in flight during a refresh is folded in by the next one`() {
        TestDatabase.reset()
        val clearing = Account("psp_clearing", "EUR", NormalSide.DEBIT)
        val merchant = Account("merchant_payable:m_1", "EUR", NormalSide.CREDIT)
        val entry = NewJournalEntry(EntryKind.CAPTURE, "p_1", null, t0, listOf(Posting(clearing, 500), Posting(merchant, -500)))

        dataSource.transaction { conn ->
            batched.post(conn, entry)
            refresh()
        }
        assertEquals(1, dataSource.transaction { batched.lag(it) }.pendingEntries, "committed after the refresh began")
        assertEquals(0, balances().getValue("psp_clearing"))

        refresh()
        assertEquals(500, balances().getValue("psp_clearing"))
        assertEquals(emptyList(), dataSource.transaction { batched.verify(it) })
    }
}
