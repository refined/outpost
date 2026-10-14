package io.github.refined.morledger

import io.ktor.client.request.get
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
    fun `demo console, API docs and spec are served`() = testApplication {
        application { module(dataSource) }
        val noRedirects = createClient { followRedirects = false }

        assertEquals("/ui/", noRedirects.get("/").headers["Location"])
        assertTrue(client.get("/ui/").bodyAsText().contains("MoR Ledger Console"))
        assertEquals(HttpStatusCode.OK, client.get("/ui/app.js").status)
        assertTrue(client.get("/docs/").bodyAsText().contains("swagger-ui"))
        assertTrue(client.get("/openapi.yaml").bodyAsText().startsWith("openapi: 3"))
    }

    @Test
    fun `unknown route is 404`() = testApplication {
        application { module(dataSource) }

        assertEquals(HttpStatusCode.NotFound, client.get("/nope").status)
    }
}
