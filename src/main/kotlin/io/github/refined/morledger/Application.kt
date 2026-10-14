package io.github.refined.morledger

import io.github.refined.morledger.api.routes
import io.github.refined.morledger.db.Database
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("io.github.refined.morledger.Application")

data class AppConfig(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = AppConfig(
            port = env["PORT"]?.toInt() ?: 8090,
            dbUrl = env["DB_URL"] ?: "jdbc:postgresql://localhost:5442/mor_ledger",
            dbUser = env["DB_USER"] ?: "mor",
            dbPassword = env["DB_PASSWORD"] ?: "mor",
        )
    }
}

fun main() {
    val config = AppConfig.fromEnv()
    val dataSource = Database.connect(config.dbUrl, config.dbUser, config.dbPassword)
    Database.migrate(dataSource)
    embeddedServer(Netty, port = config.port) { module(dataSource) }.start(wait = true)
}

@Serializable
data class ErrorResponse(val error: String, val message: String)

fun Application.module(dataSource: DataSource) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error", "Unexpected error"))
        }
    }
    routes(dataSource)
}
