package io.github.refined.morledger

import io.github.refined.morledger.api.installErrorHandling
import io.github.refined.morledger.api.routes
import io.github.refined.morledger.db.Database
import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.BalanceMode
import io.github.refined.morledger.ledger.LedgerEngine
import io.github.refined.morledger.ledger.PostgresLedger
import io.github.refined.morledger.ledger.TigerBeetleLedger
import io.github.refined.morledger.money.RefundFeePolicy
import io.github.refined.morledger.payments.PaymentService
import io.github.refined.morledger.payments.PricingSettings
import io.github.refined.morledger.reports.Analytics
import io.github.refined.morledger.reports.BalanceReport
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
                engine = LedgerEngineKind.valueOf(env["LEDGER_ENGINE"] ?: LedgerEngineKind.POSTGRES.name),
                tigerBeetleAddress = env["TB_ADDRESS"] ?: "tigerbeetle:3000",
                balanceMode = BalanceMode.valueOf(env["BALANCE_MODE"] ?: BalanceMode.STORED.name),
                hotAccountShards = (env["HOT_ACCOUNT_SHARDS"] ?: "1").toInt().also {
                    require(it in 1..64) { "HOT_ACCOUNT_SHARDS must be in [1, 64]" }
                },
                balanceRefreshMs = (env["BALANCE_REFRESH_MS"] ?: "1000").toLong().also {
                    require(it >= 0) { "BALANCE_REFRESH_MS must be >= 0" }
                },
            ),
        )
    }
}

enum class LedgerEngineKind { POSTGRES, TIGERBEETLE }

data class LedgerSettings(
    val engine: LedgerEngineKind = LedgerEngineKind.POSTGRES,
    val balanceMode: BalanceMode = BalanceMode.STORED,
    val hotAccountShards: Int = 1,
    val tigerBeetleAddress: String = "tigerbeetle:3000",
    val balanceRefreshMs: Long = 1000,
) {
    fun engine(): LedgerEngine = when (engine) {
        LedgerEngineKind.POSTGRES -> PostgresLedger(balanceMode)
        LedgerEngineKind.TIGERBEETLE -> TigerBeetleLedger.connect(tigerBeetleAddress)
    }
}

fun main() {
    val config = AppConfig.fromEnv()
    val dataSource = Database.connect(config.dbUrl, config.dbUser, config.dbPassword)
    Database.migrate(dataSource)
    embeddedServer(Netty, port = config.port) { module(dataSource, config.pricing, ledger = config.ledger) }.start(wait = true)
}

private fun Application.startBalanceRefresher(dataSource: DataSource, engine: LedgerEngine, periodMs: Long) {
    val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "balance-refresher").apply { isDaemon = true } }
    scheduler.scheduleWithFixedDelay({
        try {
            dataSource.transaction { engine.refreshBalances(it) }
        } catch (e: Exception) {
            log.warn("Balance refresh failed; retrying next tick", e)
        }
    }, periodMs, periodMs, TimeUnit.MILLISECONDS)
    monitor.subscribe(ApplicationStopped) { scheduler.shutdownNow() }
}

fun Application.module(
    dataSource: DataSource,
    pricing: PricingSettings = PricingSettings(),
    clock: Clock = Clock.systemUTC(),
    ledger: LedgerSettings = LedgerSettings(),
) {
    val engine = ledger.engine()
    log.info("Ledger engine: {}, hot-account shards: {}", engine.description, ledger.hotAccountShards)
    if (engine.refreshesBalances && ledger.balanceRefreshMs > 0) startBalanceRefresher(dataSource, engine, ledger.balanceRefreshMs)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = false })
    }
    installErrorHandling()
    routes(
        dataSource,
        PaymentService(dataSource, pricing, clock, engine, ledger.hotAccountShards),
        BalanceReport(dataSource, engine),
        engine,
        Analytics(dataSource, engine),
    )
}
