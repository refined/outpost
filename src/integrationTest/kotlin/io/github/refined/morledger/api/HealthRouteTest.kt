package io.github.refined.morledger.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {
    @Test
    fun `health checks the database`() = api {
        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"ok\""))
    }

    @Test
    fun `demo console, API docs and spec are served`() = api {
        val noRedirects = createClient { followRedirects = false }

        assertEquals("/ui/", noRedirects.get("/").headers["Location"])
        assertTrue(client.get("/ui/").bodyAsText().contains("MoR Ledger Console"))
        assertEquals(HttpStatusCode.OK, client.get("/ui/app.js").status)
        assertTrue(client.get("/docs/").bodyAsText().contains("swagger-ui"))
        assertTrue(client.get("/openapi.yaml").bodyAsText().startsWith("openapi: 3"))
    }

    @Test
    fun `unknown route is 404`() = api {
        assertEquals(HttpStatusCode.NotFound, client.get("/nope").status)
    }
}
