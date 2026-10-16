package io.github.refined.morledger.api

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.ChartOfAccounts
import io.github.refined.morledger.ledger.LedgerEngine
import io.github.refined.morledger.payments.CaptureCommand
import io.github.refined.morledger.payments.PaymentService
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
    ledger: LedgerEngine,
    analytics: Analytics,
) {
    routing {
        // Demo console, API docs and the OpenAPI spec (static files from src/main/resources/web).
        get("/") { call.respondRedirect("/ui/") }
        // no-cache: the browser revalidates, so a rebuilt console is picked up without a hard reload.
        staticResources("/ui", "web/ui") { modify { _, call -> call.response.header(HttpHeaders.CacheControl, "no-cache") } }
        staticResources("/docs", "web/docs") { modify { _, call -> call.response.header(HttpHeaders.CacheControl, "no-cache") } }
        get("/openapi.yaml") {
            val spec = Application::class.java.classLoader.getResource("web/openapi.yaml")!!.readText()
            call.respondText(spec, ContentType.parse("application/yaml"))
        }

        get("/health") {
            dataSource.transaction { conn -> conn.createStatement().use { it.execute("SELECT 1") } }
            call.respond(HealthResponse("ok", ledger.description))
        }

        route("/v1") {
            // Capture: record that a customer payment was captured for a merchant (e.g. from a PSP webhook).
            post("/payments") {
                val req = call.receive<CaptureRequest>()
                val result = payments.capture(
                    CaptureCommand(
                        paymentId = req.paymentId,
                        merchantId = req.merchantId,
                        amount = req.amount,
                        currency = req.currency,
                        taxRate = parseRate("taxRate", req.taxRate),
                        taxJurisdiction = req.taxJurisdiction,
                        capturedAt = instant("capturedAt", req.capturedAt),
                    ),
                )
                call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.value.toDto())
            }

            // Latest capture first; filters: merchantId, status (CAPTURED | PARTIALLY_REFUNDED | REFUNDED); offset pagination.
            get("/payments") {
                val q = call.request.queryParameters
                val status = q["status"]?.let { s ->
                    PaymentStatus.entries.firstOrNull { it.name == s }
                        ?: throw ValidationException("status must be one of ${PaymentStatus.entries.joinToString()}")
                }
                val (total, page) = payments.page(
                    merchantId = q["merchantId"],
                    status = status,
                    limit = parseLimit(q["limit"], default = 50),
                    offset = intParam("offset", q["offset"], default = 0, range = 0..Int.MAX_VALUE),
                )
                call.respond(PaymentsResponse(page.map { it.toDto() }, total))
            }

            get("/payments/{paymentId}") {
                call.respond(payments.get(call.parameters["paymentId"]!!).toDto())
            }

            // Refund: full when `amount` is omitted, partial otherwise.
            post("/payments/{paymentId}/refunds") {
                val req = call.receive<RefundRequest>()
                val result = payments.refund(
                    RefundCommand(
                        paymentId = call.parameters["paymentId"]!!,
                        refundId = req.refundId,
                        amount = req.amount,
                        refundedAt = instant("refundedAt", req.refundedAt),
                    ),
                )
                call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.value.toResponse())
            }

            get("/reports/balances") {
                val asOf = instant("asOf", call.request.queryParameters["asOf"])
                val currency = call.request.queryParameters["currency"]?.let { PaymentService.validateCurrency(it) }
                call.respond(reports.build(asOf, currency).toResponse())
            }

            // Journal, most recent first. Filters: paymentId, merchantId (entries touching its account); page with `before`.
            get("/journal") {
                val q = call.request.queryParameters
                val accountCode = q["merchantId"]?.let { ChartOfAccounts.MERCHANT_PAYABLE_PREFIX + it }
                val before = q["before"]?.let { it.toLongOrNull() ?: throw ValidationException("before must be an entry id") }
                val limit = parseLimit(q["limit"], default = 100)
                val entries = dataSource.transaction { ledger.entries(it, q["paymentId"], accountCode, limit, before) }
                call.respond(JournalResponse(entries.map { it.toDto() }))
            }

            // The ledger itself: every account (shards included) and a reconciliation of stored balances vs postings.
            get("/ledger/accounts") {
                val currency = call.request.queryParameters["currency"]?.let { PaymentService.validateCurrency(it) }
                val accounts = dataSource.transaction { ledger.balances(it, null, currency) }
                call.respond(AccountsResponse(accounts.map { AccountDto(it.account.code, it.account.currency, it.account.normalSide.name, it.balance) }))
            }

            get("/ledger/verify") {
                val mismatches = dataSource.transaction { ledger.verify(it) }
                call.respond(VerifyResponse(mismatches.isEmpty(), mismatches.map { MismatchDto(it.account.code, it.account.currency, it.stored, it.fromPostings) }))
            }

            analyticsRoutes(analytics)
        }
    }
}

private fun parseLimit(value: String?, default: Int): Int = value?.let {
    it.toIntOrNull()?.takeIf { n -> n in 1..1000 } ?: throw ValidationException("limit must be 1..1000")
} ?: default

private fun parseRate(field: String, value: String): BigDecimal =
    value.toBigDecimalOrNull() ?: throw ValidationException("$field must be a decimal string, e.g. \"0.21\"")
