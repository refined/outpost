package io.github.refined.morledger.ledger

import io.github.refined.morledger.money.Split
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JournalEntryValidationTest {
    private fun entry(postings: List<Posting>) = NewJournalEntry(EntryKind.CAPTURE, "p_1", null, Instant.EPOCH, postings)

    @Test
    fun `zero lines are dropped`() {
        val lines = validatedPostings(entry(ChartOfAccounts.capture("EUR", "NL", "m_1", Split(tax = 0, fee = 0, merchant = 500))))

        assertEquals(listOf(500L, -500L), lines.map { it.amount })
    }

    @Test
    fun `an unbalanced entry is rejected`() {
        val postings = ChartOfAccounts.capture("EUR", "NL", "m_1", Split(0, 0, 100)) + Posting(ChartOfAccounts.feeRevenue("EUR"), -1)

        assertFailsWith<IllegalArgumentException> { validatedPostings(entry(postings)) }
    }

    @Test
    fun `an entry mixing currencies is rejected`() {
        val postings = listOf(Posting(ChartOfAccounts.pspClearing("EUR"), 100), Posting(ChartOfAccounts.feeRevenue("USD"), -100))

        assertFailsWith<IllegalArgumentException> { validatedPostings(entry(postings)) }
    }

    @Test
    fun `an entry needs at least two non-zero lines`() {
        val postings = listOf(Posting(ChartOfAccounts.pspClearing("EUR"), 0), Posting(ChartOfAccounts.feeRevenue("EUR"), 0))

        assertFailsWith<IllegalArgumentException> { validatedPostings(entry(postings)) }
    }

    @Test
    fun `a refund mirrors the capture`() {
        val split = Split(tax = 2100, fee = 605, merchant = 9395)

        val capture = ChartOfAccounts.capture("EUR", "NL", "m_1", split)
        val refund = ChartOfAccounts.refund("EUR", "NL", "m_1", split)

        assertEquals(capture.map { it.copy(amount = -it.amount) }, refund)
    }
}
