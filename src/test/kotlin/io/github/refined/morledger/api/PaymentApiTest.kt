package io.github.refined.morledger.api

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.module
import io.github.refined.morledger.money.RefundFeePolicy
import io.github.refined.morledger.payments.PricingSettings
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentApiTest {
    @BeforeEach
    fun reset() = TestDatabase.reset()

    private fun api(
        policy: RefundFeePolicy = RefundFeePolicy.MOR_KEEPS_FEE,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { module(TestDatabase.dataSource, PricingSettings(BigDecimal("0.05"), policy)) }
        block()
    }

    private suspend fun HttpClient.capture(
        paymentId: String,
        amount: Long = 12100,
        merchantId: String = "m_1",
        currency: String = "EUR",
        taxRate: String = "0.21",
        jurisdiction: String = "NL",
        capturedAt: String = "2026-10-01T10:00:00Z",
    ): HttpResponse = postJson(
        "/v1/payments",
        """{"paymentId":"$paymentId","merchantId":"$merchantId","amount":$amount,"currency":"$currency",
           "taxRate":"$taxRate","taxJurisdiction":"$jurisdiction","capturedAt":"$capturedAt"}""",
    )

    private suspend fun HttpClient.refund(
        paymentId: String,
        refundId: String,
        amount: Long? = null,
        refundedAt: String = "2026-10-02T10:00:00Z",
    ): HttpResponse = postJson(
        "/v1/payments/$paymentId/refunds",
        """{"refundId":"$refundId",${amount?.let { "\"amount\":$it," } ?: ""}"refundedAt":"$refundedAt"}""",
    )

    private suspend fun HttpClient.postJson(path: String, body: String) = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun HttpClient.balances(query: String = ""): JsonObject = get("/v1/reports/balances$query").json()

    private fun JsonObject.taxOwed(jurisdiction: String, currency: String = "EUR"): Long? =
        getValue("taxAuthorities").jsonArray.map { it.jsonObject }
            .singleOrNull { it.str("jurisdiction") == jurisdiction && it.str("currency") == currency }?.long("amountOwed")

    private fun JsonObject.merchantBalance(merchantId: String, currency: String = "EUR"): Long? =
        getValue("merchants").jsonArray.map { it.jsonObject }
            .singleOrNull { it.str("merchantId") == merchantId && it.str("currency") == currency }?.long("balance")

    private fun JsonObject.feeRevenue(currency: String = "EUR"): Long =
        getValue("mor").jsonArray.map { it.jsonObject }.single { it.str("currency") == currency }.long("feeRevenue")

    private fun JsonObject.isBalanced() = getValue("integrity").jsonObject.getValue("balanced").jsonPrimitive.content == "true"

    @Test
    fun `capture splits the payment and shows up in the report`() = api {
        val response = client.capture("p_1")

        assertEquals(HttpStatusCode.Created, response.status)
        val payment = response.json()
        assertEquals(2100, payment.long("taxAmount"))
        assertEquals(605, payment.long("feeAmount"))
        assertEquals(9395, payment.long("merchantAmount"))
        assertEquals("CAPTURED", payment.str("status"))

        val report = client.balances()
        assertEquals(2100, report.taxOwed("NL"))
        assertEquals(9395, report.merchantBalance("m_1"))
        assertEquals(605, report.feeRevenue())
        assertTrue(report.isBalanced())
    }

    @Test
    fun `replayed capture returns the original and posts nothing`() = api {
        val first = client.capture("p_1").bodyAsText()
        val replay = client.capture("p_1")

        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(first, replay.bodyAsText())
        assertEquals(1, client.get("/v1/journal").json().getValue("entries").jsonArray.size)
        assertEquals(2100, client.balances().taxOwed("NL"))
    }

    @Test
    fun `same payment id with a different payload is a conflict`() = api {
        client.capture("p_1", amount = 12100)
        val conflict = client.capture("p_1", amount = 999)

        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("idempotency_conflict", conflict.json().str("error"))
    }

    @Test
    fun `full refund with the fee kept leaves the merchant owing the fee`() = api(RefundFeePolicy.MOR_KEEPS_FEE) {
        client.capture("p_1")
        val response = client.refund("p_1", "r_1")

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.json()
        assertEquals(12100, body.getValue("refund").jsonObject.long("amount"))
        assertEquals("REFUNDED", body.getValue("payment").jsonObject.str("status"))

        val report = client.balances()
        assertEquals(0, report.taxOwed("NL"))
        assertEquals(-605, report.merchantBalance("m_1"))
        assertEquals(605, report.feeRevenue())
        assertTrue(report.isBalanced())
    }

    @Test
    fun `full refund with the fee returned zeroes everything`() = api(RefundFeePolicy.MOR_RETURNS_FEE) {
        client.capture("p_1")
        client.refund("p_1", "r_1")

        val report = client.balances()
        assertEquals(0, report.taxOwed("NL"))
        assertEquals(0, report.merchantBalance("m_1"))
        assertEquals(0, report.feeRevenue())
    }

    @Test
    fun `partial refunds add up exactly and cannot exceed the payment`() = api(RefundFeePolicy.MOR_RETURNS_FEE) {
        client.capture("p_1", amount = 1000, taxRate = "0.19") // tax 160, fee 50, merchant 790

        assertEquals(HttpStatusCode.Created, client.refund("p_1", "r_1", amount = 333).status)
        assertEquals(HttpStatusCode.Created, client.refund("p_1", "r_2", amount = 333).status)

        val tooMuch = client.refund("p_1", "r_3", amount = 335)
        assertEquals(HttpStatusCode.UnprocessableEntity, tooMuch.status)
        assertEquals("refund_exceeds_refundable", tooMuch.json().str("error"))

        val last = client.refund("p_1", "r_4").json() // no amount: refund the rest
        assertEquals(334, last.getValue("refund").jsonObject.long("amount"))

        val payment = client.get("/v1/payments/p_1").json()
        assertEquals("REFUNDED", payment.str("status"))
        val refunded = payment.getValue("refunded").jsonObject
        assertEquals(listOf(1000L, 160L, 50L, 790L), listOf("amount", "taxAmount", "feeAmount", "merchantAmount").map { refunded.long(it) })

        val again = client.refund("p_1", "r_5")
        assertEquals("payment_fully_refunded", again.json().str("error"))
        assertTrue(client.balances().isBalanced())
        TestDatabase.assertLedgerConsistent()
    }

    @Test
    fun `replayed refund returns the original, a reused refund id with another payload conflicts`() = api {
        client.capture("p_1")
        val first = client.refund("p_1", "r_1", amount = 100)
        val replay = client.refund("p_1", "r_1", amount = 100)

        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(first.json().getValue("refund"), replay.json().getValue("refund"))
        assertEquals(HttpStatusCode.Conflict, client.refund("p_1", "r_1", amount = 200).status)
    }

    @Test
    fun `refund guards`() = api {
        val unknown = client.refund("nope", "r_1")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("payment_not_found", unknown.json().str("error"))

        client.capture("p_1", capturedAt = "2026-10-01T10:00:00Z")
        val early = client.refund("p_1", "r_1", refundedAt = "2026-09-30T10:00:00Z")
        assertEquals(HttpStatusCode.UnprocessableEntity, early.status)
        assertEquals("refund_before_capture", early.json().str("error"))
    }

    @Test
    fun `currencies and jurisdictions are reported separately`() = api {
        client.capture("p_eur", amount = 12100, currency = "EUR", jurisdiction = "NL")
        client.capture("p_usd", amount = 10000, currency = "USD", taxRate = "0", jurisdiction = "US-CA", merchantId = "m_2")
        client.capture("p_de", amount = 11900, currency = "EUR", taxRate = "0.19", jurisdiction = "DE")

        val report = client.balances()
        assertEquals(2100, report.taxOwed("NL"))
        assertEquals(1900, report.taxOwed("DE"))
        assertEquals(null, report.taxOwed("US-CA", "USD")) // 0% tax posts no tax line, so no tax account appears
        assertEquals(9500, report.merchantBalance("m_2", "USD"))
        assertEquals(500, report.feeRevenue("USD"))
        assertTrue(report.isBalanced())

        val usdOnly = client.balances("?currency=USD")
        assertEquals(1, usdOnly.getValue("mor").jsonArray.size)
    }

    @Test
    fun `report as of a date only includes earlier events`() = api {
        client.capture("p_1", capturedAt = "2026-10-01T10:00:00Z")
        client.refund("p_1", "r_1", refundedAt = "2026-10-05T10:00:00Z")

        assertEquals(2100, client.balances("?asOf=2026-10-03T00:00:00Z").taxOwed("NL"))
        assertEquals(0, client.balances("?asOf=2026-10-06T00:00:00Z").taxOwed("NL"))
        assertEquals(null, client.balances("?asOf=2026-09-01T00:00:00Z").taxOwed("NL"))
    }

    @Test
    fun `journal shows balanced debit and credit lines per entry`() = api {
        client.capture("p_1")
        client.refund("p_1", "r_1", amount = 6050)

        val entries = client.get("/v1/journal?paymentId=p_1").json().getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(listOf("REFUND", "CAPTURE"), entries.map { it.str("kind") })
        for (entry in entries) {
            val lines = (entry.getValue("postings") as JsonArray).map { it.jsonObject }
            val debits = lines.sumOf { it["debit"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0 }
            val credits = lines.sumOf { it["credit"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0 }
            assertEquals(debits, credits)
        }
    }

    @Test
    fun `payments are listed most recent first`() = api {
        client.capture("p_1")
        client.capture("p_2")

        val ids = client.get("/v1/payments").json().getValue("payments").jsonArray.map { it.jsonObject.str("paymentId") }
        assertEquals(listOf("p_2", "p_1"), ids)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/payments?limit=0").status)
    }

    @Test
    fun `invalid requests are rejected with 400`() = api {
        val cases = listOf(
            """{"paymentId":"p","merchantId":"m","amount":0,"currency":"EUR","taxRate":"0.21","taxJurisdiction":"NL"}""",
            """{"paymentId":"p","merchantId":"m","amount":100,"currency":"EURO","taxRate":"0.21","taxJurisdiction":"NL"}""",
            """{"paymentId":"p","merchantId":"m","amount":100,"currency":"EUR","taxRate":"21","taxJurisdiction":"NL"}""",
            """{"paymentId":"p","merchantId":"m","amount":100,"currency":"EUR","taxRate":"0.21","taxJurisdiction":"Netherlands"}""",
            """{"paymentId":"p","merchantId":"m","amount":1.5,"currency":"EUR","taxRate":"0.21","taxJurisdiction":"NL"}""",
            """{"paymentId":"p","merchantId":"m","amount":100,"currency":"EUR","taxRate":"0.21","taxJurisdiction":"NL","ammount":1}""",
            """{"paymentId":"p","merchantId":"m","amount":100,"currency":"EUR","taxRate":"0.21","taxJurisdiction":"NL","capturedAt":"yesterday"}""",
            """{"paymentId":"p","merchantId":"m","amount":100,"currency":"EUR","taxRate":"0.21","taxJurisdiction":"NL","capturedAt":"2999-01-01T00:00:00Z"}""",
            """{"paymentId":"has space","merchantId":"m","amount":100,"currency":"EUR","taxRate":"0.21","taxJurisdiction":"NL"}""",
            """{"merchantId":"m","amount":100,"currency":"EUR","taxRate":"0.21","taxJurisdiction":"NL"}""",
            """not json""",
        )
        for (body in cases) {
            val response = client.postJson("/v1/payments", body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals("invalid_request", response.json().str("error"), body)
        }
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/reports/balances?asOf=soon").status)
    }
}
