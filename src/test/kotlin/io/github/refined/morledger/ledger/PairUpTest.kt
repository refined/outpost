package io.github.refined.morledger.ledger

import io.github.refined.morledger.money.Split
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class PairUpTest {
    private val clearing = ChartOfAccounts.pspClearing("EUR")
    private val tax = ChartOfAccounts.taxPayable("NL", "EUR")
    private val fee = ChartOfAccounts.feeRevenue("EUR")
    private val merchant = ChartOfAccounts.merchantPayable("m_1", "EUR")

    @Test
    fun `a capture becomes one transfer per credit line`() {
        val transfers = TigerBeetleLedger.pairUp(ChartOfAccounts.capture("EUR", "NL", "m_1", Split(2100, 605, 9395)))
        assertEquals(
            listOf(Triple(clearing, tax, 2100L), Triple(clearing, fee, 605L), Triple(clearing, merchant, 9395L)),
            transfers,
        )
    }

    @Test
    fun `a refund reverses the direction`() {
        val transfers = TigerBeetleLedger.pairUp(LedgerEngine.validated(
            NewJournalEntry(EntryKind.REFUND, "p", "r", Instant.EPOCH, ChartOfAccounts.refund("EUR", "NL", "m_1", Split(1050, 0, 5000))),
        ))
        assertEquals(listOf(Triple(tax, clearing, 1050L), Triple(merchant, clearing, 5000L)), transfers)
    }
}
