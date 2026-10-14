package io.github.refined.morledger

import com.zaxxer.hikari.HikariDataSource
import io.github.refined.morledger.db.Database
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthRouteTest {
    private val postgres = PostgreSQLContainer("postgres:17-alpine")
    private lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun start() {
        postgres.start()
        dataSource = Database.connect(postgres.jdbcUrl, postgres.username, postgres.password)
        Database.migrate(dataSource)
    }

    @AfterAll
    fun stop() {
        dataSource.close()
        postgres.stop()
    }

    @Test
    fun `health checks the database`() = testApplication {
        application { module(dataSource) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"ok\""))
    }

    @Test
    fun `ledger endpoints are stubbed`() = testApplication {
        application { module(dataSource) }

        assertEquals(HttpStatusCode.NotImplemented, client.post("/v1/payments").status)
        assertEquals(HttpStatusCode.NotImplemented, client.post("/v1/payments/p-1/refunds").status)
        assertEquals(HttpStatusCode.NotImplemented, client.get("/v1/reports/balances").status)
    }
}
