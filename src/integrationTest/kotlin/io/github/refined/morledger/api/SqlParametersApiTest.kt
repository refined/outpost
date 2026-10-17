package io.github.refined.morledger.api

import io.github.refined.morledger.TestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlParametersApiTest {
    @BeforeEach
    fun reset() = TestDatabase.reset()

    @Test
    fun `quoted identifiers and search strings do not change query meaning`() = api {
        assertEquals(HttpStatusCode.Created, client.capture("p1", merchantId = "m1").status)
        assertEquals(HttpStatusCode.Created, client.capture("p2", merchantId = "m2").status)
        val input = "p1' OR '1'='1"

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/payments/${input.encodeURLPathPart()}").status)
        val payments = client.get("/v1/payments") {
            parameter("merchantId", input)
            parameter("status", "CAPTURED")
            parameter("limit", 1)
            parameter("offset", 0)
        }.json()
        assertEquals(0, payments.long("total"))
        assertTrue(payments.objects("payments").isEmpty())

        val merchants = client.get("/v1/analytics/merchants") {
            parameter("q", input)
            parameter("currency", "EUR")
            parameter("sort", "merchant")
        }.json()
        assertEquals(0, merchants.long("total"))
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/analytics/merchants/${input.encodeURLPathPart()}").status)

        for (filter in listOf("paymentId", "merchantId")) {
            val journal = client.get("/v1/journal") { parameter(filter, input) }.json()
            assertTrue(journal.objects("entries").isEmpty())
        }
        val series = client.get("/v1/analytics/timeseries") {
            parameter("currency", "EUR")
            parameter("merchantId", input)
            parameter("from", "2026-10-01T00:00:00Z")
            parameter("to", "2026-10-03T00:00:00Z")
        }.json()
        assertTrue(series.objects("points").isEmpty())
        assertEquals(2, client.getJson("/v1/payments").long("total"))
        client.assertBooksBalance()
    }
}
