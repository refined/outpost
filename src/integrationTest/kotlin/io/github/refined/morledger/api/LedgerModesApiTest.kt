package io.github.refined.morledger.api

import io.github.refined.morledger.BalanceMode
import io.github.refined.morledger.LedgerSettings
import io.github.refined.morledger.TestDatabase
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The balance mode is a performance knob: it must never change the numbers. */
class LedgerModesApiTest {
    private val t0 = Instant.parse("2026-10-01T10:00:00Z")

    @BeforeEach
    fun reset() = TestDatabase.reset()

    private data class Variant(val name: String, val settings: LedgerSettings)

    private val variants = listOf(
        Variant("stored", LedgerSettings(balanceMode = BalanceMode.STORED)),
        Variant("batched", LedgerSettings(balanceMode = BalanceMode.BATCHED, batchMaxWaitMs = 5)),
    )

    /** 12 captures in two currencies and two merchants, then partial and full refunds. */
    private suspend fun HttpClient.sampleFlow() {
        for (i in 1..12) {
            val (currency, jurisdiction, rate) = if (i % 4 == 0) Triple("USD", "US-CA", "0.0725") else Triple("EUR", "NL", "0.21")
            val response = capture(
                "p_$i", amount = 1_000L * i + 17, merchantId = if (i % 3 == 0) "m_a" else "m_b",
                currency = currency, taxRate = rate, jurisdiction = jurisdiction, capturedAt = t0.plusSeconds(i * 60L).toString(),
            )
            assertEquals(HttpStatusCode.Created, response.status)
        }
        refund("p_3", "r_3a", 1_000, t0.plusSeconds(3_600).toString())
        refund("p_3", "r_3b", null, t0.plusSeconds(7_200).toString())
        refund("p_8", "r_8", 2_500, t0.plusSeconds(3_600).toString())
    }

    @Test
    fun `every balance mode reports the same balances`() {
        val results = variants.associate { v ->
            TestDatabase.reset()
            lateinit var result: Pair<JsonObject, JsonObject>
            api(ledger = v.settings) {
                client.sampleFlow()
                client.assertBooksBalance()
                result = client.getJson("/v1/reports/balances") to client.getJson("/v1/reports/balances?asOf=${t0.plusSeconds(5_000)}")
            }
            v.name to result
        }

        val (expectedNow, expectedAsOf) = results.getValue("stored")
        assertTrue(expectedNow.objects("merchants").isNotEmpty() && expectedNow.objects("taxAuthorities").isNotEmpty())
        for ((name, result) in results) {
            assertEquals(expectedNow, result.first, "current balances, $name")
            assertEquals(expectedAsOf, result.second, "asOf balances, $name")
        }
    }

    @Test
    fun `in a batch, failing requests are rolled back alone`() =
        api(ledger = LedgerSettings(balanceMode = BalanceMode.BATCHED, batchMaxWaitMs = 50)) {
            assertEquals(HttpStatusCode.Created, client.capture("p_0").status)
            coroutineScope {
                val good = (1..10).map { i -> async(Dispatchers.IO) { client.capture("p_$i").status } }
                val conflict = async(Dispatchers.IO) { client.capture("p_0", amount = 1).status }
                val tooMuch = async(Dispatchers.IO) { client.refund("p_0", "r_0", amount = 1_000_000).status }

                assertEquals(List(10) { HttpStatusCode.Created }, good.awaitAll())
                assertEquals(HttpStatusCode.Conflict, conflict.await())
                assertEquals(HttpStatusCode.UnprocessableEntity, tooMuch.await())
            }
            assertEquals(11 * 9_395L, client.merchantBalance("m_1"))
            client.assertBooksBalance()
        }

    @Test
    fun `batched captures running concurrently lose nothing`() =
        api(ledger = LedgerSettings(balanceMode = BalanceMode.BATCHED)) {
            coroutineScope {
                (0 until 4).map { w ->
                    async(Dispatchers.IO) {
                        for (i in 1..50) assertEquals(HttpStatusCode.Created, client.capture("p_${w}_$i").status)
                    }
                }.awaitAll()
            }

            assertEquals(200 * 9_395L, client.merchantBalance("m_1"))
            client.assertBooksBalance()
        }

    private suspend fun HttpClient.merchantBalance(merchantId: String): Long? =
        getJson("/v1/reports/balances").objects("merchants").singleOrNull { it.str("merchantId") == merchantId }?.long("balance")

}
