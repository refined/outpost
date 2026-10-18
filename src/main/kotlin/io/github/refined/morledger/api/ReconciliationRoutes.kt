package io.github.refined.morledger.api

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.ChartOfAccounts
import io.github.refined.morledger.ledger.LedgerTable
import io.github.refined.morledger.payments.ValidationException
import io.github.refined.morledger.payments.validateCurrency
import io.github.refined.morledger.reports.BalanceReport
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class AccountDto(val code: String, val currency: String, val normalSide: String, val balance: Long)

@Serializable
data class AccountsResponse(val accounts: List<AccountDto>)

@Serializable
data class MismatchDto(val code: String, val currency: String, val stored: Long, val fromPostings: Long)

@Serializable
data class VerifyResponse(val ok: Boolean, val mismatches: List<MismatchDto>)

fun Route.reconciliationRoutes(dataSource: DataSource, reports: BalanceReport) {
    get("/reports/balances") {
        val asOf = instant("asOf", call.request.queryParameters["asOf"])
        val currency = call.request.queryParameters["currency"]?.let { validateCurrency(it) }
        call.respond(blocking { reports.build(asOf, currency) }.toResponse())
    }

    get("/journal") {
        val q = call.request.queryParameters
        val accountCode = q["merchantId"]?.let { ChartOfAccounts.MERCHANT_PAYABLE_PREFIX + it }
        val before = q["before"]?.let { it.toLongOrNull() ?: throw ValidationException("before must be an entry id") }
        val limit = parseLimit(q["limit"], default = 100)
        val entries = blocking { dataSource.transaction { LedgerTable.entries(it, q["paymentId"], accountCode, limit, before) } }
        call.respond(JournalResponse(entries.map { it.toDto() }))
    }

    get("/ledger/accounts") {
        val currency = call.request.queryParameters["currency"]?.let { validateCurrency(it) }
        val accounts = blocking { dataSource.transaction { LedgerTable.balances(it, null, currency) } }
        call.respond(AccountsResponse(accounts.map { AccountDto(it.account.code, it.account.currency, it.account.normalSide.name, it.balance) }))
    }

    get("/ledger/verify") {
        val mismatches = blocking { dataSource.transaction { LedgerTable.verify(it) } }
        call.respond(VerifyResponse(mismatches.isEmpty(), mismatches.map { MismatchDto(it.account.code, it.account.currency, it.stored, it.fromPostings) }))
    }
}
