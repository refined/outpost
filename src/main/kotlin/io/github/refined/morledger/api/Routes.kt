package io.github.refined.morledger.api

import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.payments.PaymentService
import io.github.refined.morledger.payments.ValidationException
import io.github.refined.morledger.reports.Analytics
import io.github.refined.morledger.reports.BalanceReport
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class HealthResponse(val status: String, val ledger: String)

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
            paymentRoutes(payments)
            reconciliationRoutes(dataSource, reports)
            analyticsRoutes(analytics)
        }
    }
}

// JDBC blocks its thread, and Ktor has only a few request threads (sized by CPU count, 3 with a 1-CPU limit):
// database work runs on the IO pool, so a request waiting for Postgres does not hold up the others.
internal suspend fun <T> blocking(block: () -> T): T = withContext(Dispatchers.IO) { block() }

internal fun parseLimit(value: String?, default: Int): Int = value?.let {
    it.toIntOrNull()?.takeIf { n -> n in 1..1000 } ?: throw ValidationException("limit must be 1..1000")
} ?: default
