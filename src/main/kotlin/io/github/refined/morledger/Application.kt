package io.github.refined.morledger

import io.github.refined.morledger.api.installErrorHandling
import io.github.refined.morledger.api.routes
import io.github.refined.morledger.db.Database
import io.github.refined.morledger.ledger.LedgerBatchCommit
import io.github.refined.morledger.ledger.LedgerWriter
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
                batchMaxSize = (env["BATCH_MAX_SIZE"] ?: "100").toInt().also { require(it in 1..10_000) { "BATCH_MAX_SIZE must be in [1, 10000]" } },
                batchMaxWaitMs = (env["BATCH_MAX_WAIT_MS"] ?: "0").toLong().also { require(it in 0..1_000) { "BATCH_MAX_WAIT_MS must be in [0, 1000]" } },
            ),
        )
    }
}

enum class BalanceMode { STORED, BATCHED }

data class LedgerSettings(
    val balanceMode: BalanceMode = BalanceMode.STORED,
    val batchMaxSize: Int = 100,
    val batchMaxWaitMs: Long = 0,
) {
    val description: String
        get() = when (balanceMode) {
            BalanceMode.STORED -> "postgres (balances: stored)"
            BalanceMode.BATCHED -> "postgres (balances: batched, up to $batchMaxSize per commit)"
        }
}

fun main() {
    val config = AppConfig.fromEnv()
    val dataSource = Database.connect(config.dbUrl, config.dbUser, config.dbPassword)
    Database.migrate(dataSource)
    embeddedServer(Netty, port = config.port) {
        module(dataSource, config.pricing, settings = config.ledger)
    }.start(wait = true)
}

fun Application.module(
    dataSource: DataSource,
    pricing: PricingSettings = PricingSettings(),
    clock: Clock = Clock.systemUTC(),
    settings: LedgerSettings = LedgerSettings(),
) {
    val ledger = LedgerWriter()
    val batchCommit = if (settings.balanceMode == BalanceMode.BATCHED) {
        LedgerBatchCommit(dataSource, ledger, settings.batchMaxSize, settings.batchMaxWaitMs).start().also { batchCommit ->
            monitor.subscribe(ApplicationStopped) {
                log.info("Batch commit: {} items in {} batches", batchCommit.items, batchCommit.batches)
                batchCommit.close()
            }
        }
    } else {
        null
    }
    log.info("Ledger: {}", settings.description)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = false })
    }
    installErrorHandling()
    routes(
        dataSource,
        PaymentService(dataSource, pricing, clock, ledger, batchCommit),
        BalanceReport(dataSource),
        settings.description,
        Analytics(dataSource),
    )
}
