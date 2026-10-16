package io.github.refined.morledger.api

import io.github.refined.morledger.LedgerSettings
import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.module
import io.github.refined.morledger.payments.PricingSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.assertTrue

fun api(
    pricing: PricingSettings = PricingSettings(),
    ledger: LedgerSettings = LedgerSettings(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application { module(TestDatabase.dataSource, pricing, ledger = ledger) }
    block()
}

suspend fun HttpClient.postJson(path: String, body: String): HttpResponse = post(path) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

suspend fun HttpClient.capture(
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

suspend fun HttpClient.refund(
    paymentId: String,
    refundId: String,
    amount: Long? = null,
    refundedAt: String = "2026-10-02T10:00:00Z",
): HttpResponse = postJson(
    "/v1/payments/$paymentId/refunds",
    """{"refundId":"$refundId",${amount?.let { "\"amount\":$it," } ?: ""}"refundedAt":"$refundedAt"}""",
)

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

suspend fun HttpClient.getJson(path: String): JsonObject = get(path).json()

fun JsonObject.obj(key: String) = getValue(key).jsonObject

fun JsonObject.objects(key: String) = getValue(key).jsonArray.map { it.jsonObject }

fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long

fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

/** Every entry balances and every stored running balance equals the sum of its postings. */
suspend fun HttpClient.assertBooksBalance() {
    val report = getJson("/v1/reports/balances")
    assertTrue(report.obj("integrity").str("balanced") == "true", "report integrity: $report")
    val verify = getJson("/v1/ledger/verify")
    assertTrue(verify.str("ok") == "true", "stored balances differ from postings: $verify")
}
