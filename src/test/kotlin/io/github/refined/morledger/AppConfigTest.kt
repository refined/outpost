package io.github.refined.morledger

import io.github.refined.morledger.money.RefundFeePolicy
import io.github.refined.morledger.payments.PricingSettings
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun `defaults match docker-compose`() {
        val config = AppConfig.fromEnv(emptyMap())

        assertEquals(8090, config.port)
        assertEquals("jdbc:postgresql://localhost:5442/mor_ledger", config.dbUrl)
        assertEquals("mor", config.dbUser)
        assertEquals("mor", config.dbPassword)
        assertEquals(PricingSettings(BigDecimal("0.05"), RefundFeePolicy.MOR_KEEPS_FEE), config.pricing)
    }

    @Test
    fun `environment overrides defaults`() {
        val config = AppConfig.fromEnv(
            mapOf(
                "PORT" to "9000",
                "DB_URL" to "jdbc:postgresql://db:5432/x",
                "DB_USER" to "u",
                "DB_PASSWORD" to "p",
                "MOR_FEE_RATE" to "0.029",
                "REFUND_FEE_POLICY" to "MOR_RETURNS_FEE",
            ),
        )

        assertEquals(
            AppConfig(9000, "jdbc:postgresql://db:5432/x", "u", "p", PricingSettings(BigDecimal("0.029"), RefundFeePolicy.MOR_RETURNS_FEE)),
            config,
        )
    }

    @Test
    fun `invalid pricing config fails fast`() {
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("MOR_FEE_RATE" to "0.6")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("REFUND_FEE_POLICY" to "SOMETIMES")) }
    }
}
