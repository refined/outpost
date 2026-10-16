package io.github.refined.morledger.api

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.ChartOfAccounts
import io.github.refined.morledger.ledger.LedgerEngine
import io.github.refined.morledger.payments.CaptureCommand
import io.github.refined.morledger.payments.PaymentService
import io.github.refined.morledger.payments.RefundCommand
import io.github.refined.morledger.payments.ValidationException
import io.github.refined.morledger.reports.BalanceReport
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.sql.DataSource

@Serializable
data class HealthResponse(val status: String)

fun Application.routes(dataSource: DataSource, payments: PaymentService, reports: BalanceReport, ledger: LedgerEngine) {
    routing {
        // Demo console, API docs and the OpenAPI spec (static files from src/main/resources/web).
        get("/") { call.respondRedirect("/ui/") }
        staticResources("/ui", "web/ui")
        staticResources("/docs", "web/docs")
        get("/openapi.yaml") {
            val spec = Application::class.java.classLoader.getResource("web/openapi.yaml")!!.readText()
            call.respondText(spec, ContentType.parse("application/yaml"))
        }

        get("/health") {
            dataSource.transaction { conn -> conn.createStatement().use { it.execute("SELECT 1") } }
            call.respond(HealthResponse("ok"))
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
                        capturedAt = parseInstant("capturedAt", req.capturedAt),
                    ),
                )
                call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.value.toDto())
            }

            get("/payments") {
                val limit = parseLimit(call.request.queryParameters["limit"], default = 50)
                call.respond(PaymentsResponse(payments.recent(limit).map { it.toDto() }))
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
                        refundedAt = parseInstant("refundedAt", req.refundedAt),
                    ),
                )
                call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.value.toResponse())
            }

            get("/reports/balances") {
                val asOf = parseInstant("asOf", call.request.queryParameters["asOf"])
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
        }
    }
}

private fun parseInstant(field: String, value: String?): Instant? = value?.let {
    try {
        Instant.parse(it)
    } catch (e: DateTimeParseException) {
        throw ValidationException("$field must be an ISO-8601 UTC timestamp, e.g. 2026-10-14T10:00:00Z")
    }
}

private fun parseLimit(value: String?, default: Int): Int = value?.let {
    it.toIntOrNull()?.takeIf { n -> n in 1..1000 } ?: throw ValidationException("limit must be 1..1000")
} ?: default

private fun parseRate(field: String, value: String): BigDecimal =
    value.toBigDecimalOrNull() ?: throw ValidationException("$field must be a decimal string, e.g. \"0.21\"")
