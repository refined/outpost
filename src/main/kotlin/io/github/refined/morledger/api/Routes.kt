package io.github.refined.morledger.api

import io.github.refined.morledger.ErrorResponse
import io.github.refined.morledger.db.transaction
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class HealthResponse(val status: String)

fun Application.routes(dataSource: DataSource) {
    routing {
        get("/health") {
            dataSource.transaction { conn -> conn.createStatement().use { it.execute("SELECT 1") } }
            call.respond(HealthResponse("ok"))
        }

        // Provisional API surface: the request/response shapes are defined in the next iteration.
        route("/v1") {
            post("/payments") { call.notImplemented("capture") }
            post("/payments/{paymentId}/refunds") { call.notImplemented("refund") }
            get("/reports/balances") { call.notImplemented("balances report") }
        }
    }
}

private suspend fun RoutingCall.notImplemented(what: String) =
    respond(HttpStatusCode.NotImplemented, ErrorResponse("not_implemented", "$what is not implemented yet"))
