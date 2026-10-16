package io.github.refined.morledger.api

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.module
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsApiTest {
    @BeforeEach
    fun reset() = TestDatabase.reset()

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(TestDatabase.dataSource) }
        block()
    }

    private suspend fun HttpClient.postJson(path: String, body: String) = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.capture(id: String, merchant: String, amount: Long, day: Int, currency: String = "EUR", jurisdiction: String = "NL", rate: String = "0.21") {
        val response = postJson(
            "/v1/payments",
            """{"paymentId":"$id","merchantId":"$merchant","amount":$amount,"currency":"$currency","taxRate":"$rate",
               "taxJurisdiction":"$jurisdiction","capturedAt":"2026-09-${"%02d".format(day)}T10:00:00Z"}""",
        )
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.refund(paymentId: String, refundId: String, amount: Long?, day: Int) {
        val amountField = amount?.let { "\"amount\":$it," } ?: ""
        val response = postJson("/v1/payments/$paymentId/refunds", """{"refundId":"$refundId",$amountField"refundedAt":"2026-09-${"%02d".format(day)}T12:00:00Z"}""")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    /** acme: two NL payments and one DE (EUR), one partial refund; globex: one USD payment fully refunded. */
    private suspend fun HttpClient.seed() {
        capture("p1", "acme", 12_100, day = 1) // tax 2100, fee 605, merchant 9395
        capture("p2", "acme", 12_100, day = 1)
        capture("p3", "acme", 11_900, day = 3, jurisdiction = "DE", rate = "0.19") // tax 1900, fee 595, merchant 9405
        capture("p4", "globex", 10_000, day = 2, currency = "USD", jurisdiction = "US-CA", rate = "0") // fee 500, merchant 9500
        refund("p1", "r1", 6_050, day = 4) // tax 1050, merchant 5000 (fee kept)
        refund("p4", "r4", null, day = 5) // merchant 10000
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `summary totals per currency, with jurisdictions`() = api {
        client.seed()

        val eur = client.get("/v1/analytics/summary").json().getValue("currencies").jsonArray.map { it.jsonObject }.single { it.str("currency") == "EUR" }
        assertEquals(1, eur.long("merchants"))
        assertEquals(36_100, eur.obj("captured").long("gross"))
        assertEquals(6_100, eur.obj("captured").long("tax"))
        assertEquals(6_050, eur.obj("refunded").long("gross"))
        assertEquals(30_050, eur.obj("net").long("gross"))
        val nl = eur.getValue("byJurisdiction").jsonArray.map { it.jsonObject }.single { it.str("jurisdiction") == "NL" }
        assertEquals(4_200 - 1_050, nl.long("netTax"))

        val filtered = client.get("/v1/analytics/summary?currency=USD&to=2026-09-04T00:00:00Z").json().getValue("currencies").jsonArray
        assertEquals(1, filtered.size)
        assertEquals(0, filtered.single().jsonObject.obj("refunded").long("gross")) // the USD refund is on day 5
    }

    @Test
    fun `timeseries fills empty days and carries the merchant's running balance`() = api {
        client.seed()

        val points = client.get("/v1/analytics/timeseries?currency=EUR&merchantId=acme").json().getValue("points").jsonArray.map { it.jsonObject }
        assertEquals(listOf("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04"), points.map { it.str("start") })
        assertEquals(listOf(18_790L, 18_790L, 28_195L, 23_195L), points.map { it.long("merchantBalance") })
        assertEquals(0, points[1].obj("captured").long("gross"))

        val fromDay3 = client.get("/v1/analytics/timeseries?currency=EUR&merchantId=acme&from=2026-09-03T00:00:00Z").json()
            .getValue("points").jsonArray.map { it.jsonObject }
        assertEquals(28_195, fromDay3.first().long("merchantBalance")) // opening balance before the window included

        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/analytics/timeseries").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/analytics/timeseries?currency=EUR&bucket=year").status)
    }

    @Test
    fun `merchant breakdown is reconciled with the ledger and sortable`() = api {
        client.seed()

        val page = client.get("/v1/analytics/merchants?sort=refundRate").json()
        assertEquals(2, page.long("total"))
        val rows = page.getValue("merchants").jsonArray.map { it.jsonObject }
        assertEquals(listOf("globex", "acme"), rows.map { it.str("merchantId") })
        assertTrue(rows.all { it.getValue("reconciled").jsonPrimitive.content == "true" })

        val globex = rows.first()
        assertEquals(1.0, globex.getValue("refundRate").jsonPrimitive.content.toDouble())
        assertEquals(-500, globex.long("balance")) // fee kept on refund: the merchant owes it
        assertEquals(-500, globex.long("ledgerBalance"))

        val acme = rows.last()
        assertEquals(3, acme.obj("captured").long("count"))
        assertEquals(23_195, acme.long("balance"))

        val searched = client.get("/v1/analytics/merchants?q=glo").json()
        assertEquals(1, searched.long("total"))
    }

    @Test
    fun `merchant detail breaks down by jurisdiction`() = api {
        client.seed()

        val detail = client.get("/v1/analytics/merchants/acme").json()
        val jurisdictions = detail.getValue("jurisdictions").jsonArray.map { it.jsonObject }
        assertEquals(setOf("NL", "DE"), jurisdictions.map { it.str("jurisdiction") }.toSet())
        assertEquals(2, jurisdictions.single { it.str("jurisdiction") == "NL" }.obj("captured").long("count"))

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/analytics/merchants/nobody").status)
    }

    @Test
    fun `payments can be filtered by merchant and status, with a total for paging`() = api {
        client.seed()

        val acme = client.get("/v1/payments?merchantId=acme&limit=2").json()
        assertEquals(3, acme.long("total"))
        assertEquals(listOf("p3", "p2"), acme.getValue("payments").jsonArray.map { it.jsonObject.str("paymentId") }) // latest capture first

        val second = client.get("/v1/payments?merchantId=acme&limit=2&offset=2").json()
        assertEquals(listOf("p1"), second.getValue("payments").jsonArray.map { it.jsonObject.str("paymentId") })

        val refunded = client.get("/v1/payments?status=REFUNDED").json()
        assertEquals(listOf("p4"), refunded.getValue("payments").jsonArray.map { it.jsonObject.str("paymentId") })
        val partial = client.get("/v1/payments?status=PARTIALLY_REFUNDED").json()
        assertEquals(listOf("p1"), partial.getValue("payments").jsonArray.map { it.jsonObject.str("paymentId") })

        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/payments?status=LOST").status)
    }

    @Test
    fun `ledger accounts list every account and verify confirms stored balances`() = api {
        client.seed()

        val accounts = client.get("/v1/ledger/accounts?currency=EUR").json().getValue("accounts").jsonArray.map { it.jsonObject }
        val acme = accounts.single { it.str("code") == "merchant_payable:acme" }
        assertEquals(23_195, acme.long("balance"))
        assertTrue(accounts.all { it.str("currency") == "EUR" })

        val verify = client.get("/v1/ledger/verify").json()
        assertEquals("true", verify.getValue("ok").jsonPrimitive.content)
        assertTrue(client.get("/health").bodyAsText().contains("postgres"))
    }

    @Test
    fun `journal can be filtered by merchant and paged`() = api {
        client.seed()

        val page1 = client.get("/v1/journal?merchantId=acme&limit=2").json().getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(2, page1.size)
        val before = page1.last().long("id")
        val page2 = client.get("/v1/journal?merchantId=acme&before=$before").json().getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(2, page2.size) // acme has 4 entries: 3 captures + 1 refund
        assertTrue(page2.all { it.long("id") < before })
    }
}
