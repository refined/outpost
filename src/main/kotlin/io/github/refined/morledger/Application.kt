package io.github.refined.morledger

import io.github.refined.morledger.api.installErrorHandling
import io.github.refined.morledger.api.routes
import io.github.refined.morledger.db.Database
import io.github.refined.morledger.ledger.BalanceMode
import io.github.refined.morledger.ledger.LedgerEngine
import io.github.refined.morledger.ledger.PostgresLedger
import io.github.refined.morledger.money.RefundFeePolicy
import io.github.refined.morledger.payments.PaymentService
import io.github.refined.morledger.payments.PricingSettings
import io.github.refined.morledger.reports.BalanceReport
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Clock
import javax.sql.DataSource

data class AppConfig(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val pricing: PricingSettings,
    val ledger: LedgerSettings = LedgerSettings(),
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = AppConfig(
            port = env["PORT"]?.toInt() ?: 8090,
            dbUrl = env["DB_URL"] ?: "jdbc:postgresql://localhost:5442/mor_ledger",
            dbUser = env["DB_USER"] ?: "mor",
            dbPassword = env["DB_PASSWORD"] ?: "mor",
            pricing = PricingSettings(
                feeRate = (env["MOR_FEE_RATE"] ?: "0.05").toBigDecimal().also {
                    require(it >= BigDecimal.ZERO && it <= BigDecimal("0.5")) { "MOR_FEE_RATE must be in [0, 0.5]" }
                },
                refundFeePolicy = RefundFeePolicy.valueOf(env["REFUND_FEE_POLICY"] ?: RefundFeePolicy.MOR_KEEPS_FEE.name),
            ),
            ledger = LedgerSettings(
                balanceMode = BalanceMode.valueOf(env["BALANCE_MODE"] ?: BalanceMode.STORED.name),
                hotAccountShards = (env["HOT_ACCOUNT_SHARDS"] ?: "1").toInt().also {
                    require(it in 1..64) { "HOT_ACCOUNT_SHARDS must be in [1, 64]" }
                },
            ),
        )
    }
}

/** Ledger engine options; the non-default ones exist to compare approaches (docs/performance.md). */
data class LedgerSettings(
    val balanceMode: BalanceMode = BalanceMode.STORED,
    val hotAccountShards: Int = 1,
) {
    fun engine(): LedgerEngine = PostgresLedger(balanceMode)
}

fun main() {
    val config = AppConfig.fromEnv()
    val dataSource = Database.connect(config.dbUrl, config.dbUser, config.dbPassword)
    Database.migrate(dataSource)
    embeddedServer(Netty, port = config.port) { module(dataSource, config.pricing, ledger = config.ledger) }.start(wait = true)
}

fun Application.module(
    dataSource: DataSource,
    pricing: PricingSettings = PricingSettings(),
    clock: Clock = Clock.systemUTC(),
    ledger: LedgerSettings = LedgerSettings(),
) {
    val engine = ledger.engine()
    log.info("Ledger engine: {}, hot-account shards: {}", engine.description, ledger.hotAccountShards)
    install(ContentNegotiation) {
        // Strict: a misspelled optional field (e.g. "ammount" on a refund) must fail, not silently become a full refund.
        json(Json { ignoreUnknownKeys = false })
    }
    installErrorHandling()
    routes(
        dataSource,
        PaymentService(dataSource, pricing, clock, engine, ledger.hotAccountShards),
        BalanceReport(dataSource, engine),
        engine,
    )
}
