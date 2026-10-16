package io.github.refined.morledger.api

import io.github.refined.morledger.LedgerSettings
import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.ledger.BalanceMode
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The balance modes and hot-account sharding are performance knobs: they must never change the numbers. */
class LedgerModesApiTest {
    private val t0 = Instant.parse("2026-10-01T10:00:00Z")

    @BeforeEach
    fun reset() = TestDatabase.reset()

    private data class Variant(val name: String, val settings: LedgerSettings)

    private val variants = listOf(
        Variant("stored", LedgerSettings(balanceMode = BalanceMode.STORED)),
        Variant("computed", LedgerSettings(balanceMode = BalanceMode.COMPUTED)),
        Variant("stored + 4 shards", LedgerSettings(balanceMode = BalanceMode.STORED, hotAccountShards = 4)),
        Variant("computed + 4 shards", LedgerSettings(balanceMode = BalanceMode.COMPUTED, hotAccountShards = 4)),
        // Never refreshed: every read is snapshot (empty) + tail.
        Variant("snapshot, no refresh", LedgerSettings(balanceMode = BalanceMode.SNAPSHOT, balanceRefreshMs = 0)),
        Variant("snapshot + 4 shards", LedgerSettings(balanceMode = BalanceMode.SNAPSHOT, hotAccountShards = 4, balanceRefreshMs = 20)),
        Variant("batched", LedgerSettings(balanceMode = BalanceMode.BATCHED, balanceRefreshMs = 20)),
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
    fun `every balance mode and shard count reports the same balances`() {
        val results = variants.associate { v ->
            TestDatabase.reset()
            lateinit var result: Pair<JsonObject, JsonObject>
            api(ledger = v.settings) {
                client.sampleFlow()
                if (v.settings.balanceRefreshMs > 0) client.awaitSnapshotCaughtUp()
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
    fun `shards spread hot accounts and a refund lands on its capture's shard`() = api(ledger = LedgerSettings(hotAccountShards = 8)) {
        for (i in 1..40) client.capture("p_$i", amount = 10_000)
        client.refund("p_7", "r_7")

        val codes = client.getJson("/v1/ledger/accounts").objects("accounts").map { it.str("code") }
        assertTrue(codes.count { it.startsWith("fee_revenue#") } > 1, "fee revenue spread over shards: $codes")
        assertEquals(listOf("merchant_payable:m_1"), codes.filter { it.startsWith("merchant_payable") })

        val entries = client.getJson("/v1/journal?paymentId=p_7").objects("entries")
        val clearingAccounts = entries.map { entry -> entry.objects("postings").single { it.str("account").startsWith("psp_clearing") }.str("account") }
        assertEquals(1, clearingAccounts.distinct().size, "capture and refund on one shard: $clearingAccounts")
        client.assertBooksBalance()
    }

    @Test
    fun `batched reads lag until a refresh, snapshot reads never do`() {
        val twoCaptures = 2 * 9_395L
        api(ledger = LedgerSettings(balanceMode = BalanceMode.BATCHED, balanceRefreshMs = 0)) {
            client.capture("p_1")
            client.capture("p_2")
            assertEquals(2, client.getJson("/v1/ledger/lag").long("pendingEntries"))
            assertEquals(0, client.merchantBalance("m_1") ?: 0, "nothing folded in yet")
        }
        api(ledger = LedgerSettings(balanceMode = BalanceMode.SNAPSHOT, balanceRefreshMs = 0)) {
            assertEquals(twoCaptures, client.merchantBalance("m_1"), "snapshot + tail is exact without a refresh")
        }
        api(ledger = LedgerSettings(balanceMode = BalanceMode.BATCHED, balanceRefreshMs = 20)) {
            client.awaitSnapshotCaughtUp()
            assertEquals(twoCaptures, client.merchantBalance("m_1"))
            client.assertBooksBalance()
        }
    }

    @Test
    fun `refreshing while captures run concurrently loses nothing`() =
        api(ledger = LedgerSettings(balanceMode = BalanceMode.BATCHED, balanceRefreshMs = 1)) {
            coroutineScope {
                (0 until 4).map { w ->
                    async(Dispatchers.IO) {
                        for (i in 1..50) assertEquals(HttpStatusCode.Created, client.capture("p_${w}_$i").status)
                    }
                }.awaitAll()
            }

            client.awaitSnapshotCaughtUp()
            assertEquals(200 * 9_395L, client.merchantBalance("m_1"))
            client.assertBooksBalance()
        }

    private suspend fun HttpClient.merchantBalance(merchantId: String): Long? =
        getJson("/v1/reports/balances").objects("merchants").singleOrNull { it.str("merchantId") == merchantId }?.long("balance")

    /** No-op for modes without a snapshot: their lag is always zero. */
    private suspend fun HttpClient.awaitSnapshotCaughtUp() = withTimeout(10_000) {
        while (getJson("/v1/ledger/lag").long("pendingEntries") > 0) delay(20)
    }
}
