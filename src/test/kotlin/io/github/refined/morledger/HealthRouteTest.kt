package io.github.refined.morledger

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {
    private val dataSource = TestDatabase.dataSource

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

    @Test
    fun `unknown route is 404`() = testApplication {
        application { module(dataSource) }

        assertEquals(HttpStatusCode.NotFound, client.get("/nope").status)
    }
}
