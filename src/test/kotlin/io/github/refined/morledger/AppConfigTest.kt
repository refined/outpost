package io.github.refined.morledger

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AppConfigTest {
    @Test
    fun `defaults match docker-compose`() {
        val config = AppConfig.fromEnv(emptyMap())

        assertEquals(8090, config.port)
        assertEquals("jdbc:postgresql://localhost:5442/mor_ledger", config.dbUrl)
        assertEquals("mor", config.dbUser)
        assertEquals("mor", config.dbPassword)
    }

    @Test
    fun `environment overrides defaults`() {
        val config = AppConfig.fromEnv(
            mapOf(
                "PORT" to "9000",
                "DB_URL" to "jdbc:postgresql://db:5432/x",
                "DB_USER" to "u",
                "DB_PASSWORD" to "p",
            ),
        )

        assertEquals(AppConfig(9000, "jdbc:postgresql://db:5432/x", "u", "p"), config)
    }
}
