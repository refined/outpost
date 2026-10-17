package io.github.refined.morledger.api

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.ChartOfAccounts
import io.github.refined.morledger.ledger.LedgerTable
import io.github.refined.morledger.payments.CaptureCommand
import io.github.refined.morledger.payments.PaymentService
import io.github.refined.morledger.payments.validateCurrency
import io.github.refined.morledger.payments.PaymentStatus
import io.github.refined.morledger.payments.RefundCommand
import io.github.refined.morledger.payments.ValidationException
import io.github.refined.morledger.reports.Analytics
import io.github.refined.morledger.reports.BalanceReport
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import javax.sql.DataSource

@Serializable
data class HealthResponse(val status: String, val ledger: String)

@Serializable
data class AccountDto(val code: String, val currency: String, val normalSide: String, val balance: Long)

@Serializable
data class AccountsResponse(val accounts: List<AccountDto>)

@Serializable
data class MismatchDto(val code: String, val currency: String, val stored: Long, val fromPostings: Long)

@Serializable
data class VerifyResponse(val ok: Boolean, val mismatches: List<MismatchDto>)


fun Application.routes(
    dataSource: DataSource,
    payments: PaymentService,
    reports: BalanceReport,
    ledgerDescription: String,
    analytics: Analytics,
) {
    routing {
        get("/") { call.respondRedirect("/ui/") }
        staticResources("/ui", "web/ui") { modify { _, call -> call.response.header(HttpHeaders.CacheControl, "no-cache") } }
        staticResources("/docs", "web/docs") { modify { _, call -> call.response.header(HttpHeaders.CacheControl, "no-cache") } }
        get("/openapi.yaml") {
            val spec = Application::class.java.classLoader.getResource("web/openapi.yaml")!!.readText()
            call.respondText(spec, ContentType.parse("application/yaml"))
        }

        get("/health") {
            blocking { dataSource.transaction { conn -> conn.createStatement().use { it.execute("SELECT 1") } } }
            call.respond(HealthResponse("ok", ledgerDescription))
        }

        route("/v1") {
            post("/payments") {
                val req = call.receive<CaptureRequest>()
                val cmd = CaptureCommand(
                    paymentId = req.paymentId,
                    merchantId = req.merchantId,
                    amount = req.amount,
                    currency = req.currency,
                    taxRate = parseRate("taxRate", req.taxRate),
                    taxJurisdiction = req.taxJurisdiction,
                    capturedAt = instant("capturedAt", req.capturedAt),
                )
                val result = payments.capture(cmd)
                call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.value.toDto())
            }

            get("/payments") {
                val q = call.request.queryParameters
                val status = q["status"]?.let { s ->
                    PaymentStatus.entries.firstOrNull { it.name == s }
                        ?: throw ValidationException("status must be one of ${PaymentStatus.entries.joinToString()}")
                }
                val limit = parseLimit(q["limit"], default = 50)
                val offset = intParam("offset", q["offset"], default = 0, range = 0..Int.MAX_VALUE)
                val page = blocking { payments.page(q["merchantId"], status, limit, offset) }
                call.respond(PaymentsResponse(page.rows.map { it.toDto() }, page.total))
            }

            get("/payments/{paymentId}") {
                val paymentId = call.parameters["paymentId"]!!
                call.respond(blocking { payments.get(paymentId) }.toDto())
            }

            post("/payments/{paymentId}/refunds") {
                val req = call.receive<RefundRequest>()
                val cmd = RefundCommand(
                    paymentId = call.parameters["paymentId"]!!,
                    refundId = req.refundId,
                    amount = req.amount,
                    refundedAt = instant("refundedAt", req.refundedAt),
                )
                val result = payments.refund(cmd)
                call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.value.toResponse())
            }

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

            analyticsRoutes(analytics)
        }
    }
}

// JDBC blocks its thread, and Ktor has only a few request threads (sized by CPU count, 3 with a 1-CPU limit):
// database work runs on the IO pool, so a request waiting for Postgres does not hold up the others.
internal suspend fun <T> blocking(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private fun parseLimit(value: String?, default: Int): Int = value?.let {
    it.toIntOrNull()?.takeIf { n -> n in 1..1000 } ?: throw ValidationException("limit must be 1..1000")
} ?: default

private fun parseRate(field: String, value: String): BigDecimal =
    value.toBigDecimalOrNull() ?: throw ValidationException("$field must be a decimal string, e.g. \"0.21\"")
