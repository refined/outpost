package io.github.refined.morledger.reports

import io.github.refined.morledger.ledger.Account
import io.github.refined.morledger.ledger.AccountBalance
import io.github.refined.morledger.ledger.NormalSide
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BalanceReportTest {
    private fun balance(code: String, signedSum: Long, currency: String = "EUR") =
        AccountBalance(Account(code, currency, if (code == "psp_clearing") NormalSide.DEBIT else NormalSide.CREDIT), signedSum)

    @Test
    fun `accounts are grouped into tax owed, merchant balances and the MoR position`() {
        // One capture of 12_100: tax 2_100, fee 605, merchant 9_395.
        val report = BalanceReport.fromLedger(
            listOf(
                balance("psp_clearing", 12_100),
                balance("tax_payable:NL", -2_100),
                balance("fee_revenue", -605),
                balance("merchant_payable:m_1", -9_395),
            ),
            asOf = null,
        )

        assertEquals(listOf(TaxOwed("NL", "EUR", 2_100)), report.taxAuthorities)
        assertEquals(listOf(MerchantBalance("m_1", "EUR", 9_395)), report.merchants)
        assertEquals(listOf(MorPosition("EUR", feeRevenue = 605, pspClearing = 12_100)), report.mor)
        assertEquals(Integrity(balanced = true, unbalancedCurrencies = emptyList()), report.integrity)
    }

    @Test
    fun `a currency whose balances do not sum to zero is flagged`() {
        val report = BalanceReport.fromLedger(
            listOf(
                balance("psp_clearing", 100),
                balance("merchant_payable:m_1", -100),
                balance("psp_clearing", 50, currency = "USD"),
            ),
            asOf = null,
        )

        assertEquals(Integrity(balanced = false, unbalancedCurrencies = listOf("USD")), report.integrity)
    }
}
