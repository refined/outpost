package io.github.refined.morledger.reports

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.AccountBalance
import io.github.refined.morledger.ledger.ChartOfAccounts
import io.github.refined.morledger.ledger.LedgerEngine
import io.github.refined.morledger.ledger.PostgresLedger
import java.time.Instant
import javax.sql.DataSource

data class TaxOwed(val jurisdiction: String, val currency: String, val amountOwed: Long)

data class MerchantBalance(val merchantId: String, val currency: String, val balance: Long)

data class MorPosition(val currency: String, val feeRevenue: Long, val pspClearing: Long)

data class Integrity(val balanced: Boolean, val unbalancedCurrencies: List<String>)

data class Balances(
    val asOf: Instant?,
    val taxAuthorities: List<TaxOwed>,
    val merchants: List<MerchantBalance>,
    val mor: List<MorPosition>,
    val integrity: Integrity,
)

class BalanceReport(private val dataSource: DataSource, private val ledger: LedgerEngine = PostgresLedger()) {
    fun build(asOf: Instant? = null, currency: String? = null): Balances =
        fromLedger(dataSource.transaction { ledger.balances(it, asOf, currency) }, asOf)

    companion object {
        fun fromLedger(rawBalances: List<AccountBalance>, asOf: Instant?): Balances {
            val balances = rawBalances
                .groupBy { it.account.copy(code = ChartOfAccounts.baseCode(it.account.code)) }
                .map { (account, shards) -> AccountBalance(account, shards.sumOf { it.signedSum }) }
            val taxes = mutableListOf<TaxOwed>()
            val merchants = mutableListOf<MerchantBalance>()
            for (b in balances) {
                val code = b.account.code
                when {
                    code.startsWith(ChartOfAccounts.TAX_PAYABLE_PREFIX) ->
                        taxes += TaxOwed(code.removePrefix(ChartOfAccounts.TAX_PAYABLE_PREFIX), b.account.currency, b.balance)
                    code.startsWith(ChartOfAccounts.MERCHANT_PAYABLE_PREFIX) ->
                        merchants += MerchantBalance(code.removePrefix(ChartOfAccounts.MERCHANT_PAYABLE_PREFIX), b.account.currency, b.balance)
                }
            }
            val byCurrency = balances.groupBy { it.account.currency }
            val mor = byCurrency.map { (currency, list) ->
                MorPosition(
                    currency = currency,
                    feeRevenue = list.filter { it.account.code == ChartOfAccounts.FEE_REVENUE }.sumOf { it.balance },
                    pspClearing = list.filter { it.account.code == ChartOfAccounts.PSP_CLEARING }.sumOf { it.balance },
                )
            }
            val unbalanced = byCurrency.filterValues { list -> list.sumOf { it.signedSum } != 0L }.keys.sorted()
            return Balances(
                asOf = asOf,
                taxAuthorities = taxes.sortedWith(compareBy({ it.currency }, { it.jurisdiction })),
                merchants = merchants.sortedWith(compareBy({ it.currency }, { it.merchantId })),
                mor = mor.sortedBy { it.currency },
                integrity = Integrity(unbalanced.isEmpty(), unbalanced),
            )
        }
    }
}
