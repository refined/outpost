package io.github.refined.morledger.api

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.module
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrencyApiTest {
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()

    @BeforeEach
    fun reset() = TestDatabase.reset()

    private fun concurrentApi(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(TestDatabase.dataSource)
            intercept(ApplicationCallPipeline.Monitoring) {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                try {
                    proceed()
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        block()
    }

    private suspend fun <T> parallel(n: Int, request: suspend (Int) -> T): List<T> {
        val results = coroutineScope { (0 until n).map { i -> async(Dispatchers.IO) { request(i) } }.awaitAll() }
        // Guards the test itself: if the server handled requests one at a time, nothing here would race.
        assertTrue(maxInFlight.get() > 1, "requests never overlapped")
        return results
    }

    @Test
    fun `parallel partial refunds never exceed the captured amount`() = concurrentApi {
        client.capture("p_1", amount = 10_000)

        // 20 refunds of 700 race for 10_000: exactly 14 fit, the rest must be rejected.
        val statuses = parallel(20) { i -> client.refund("p_1", "r_$i", amount = 700).status }

        assertEquals(14, statuses.count { it == HttpStatusCode.Created })
        assertEquals(6, statuses.count { it == HttpStatusCode.UnprocessableEntity })
        assertEquals(9_800, client.getJson("/v1/payments/p_1").obj("refunded").long("amount"))
        client.assertBooksBalance()
    }

    @Test
    fun `parallel captures on shared accounts lose no update and do not deadlock`() = concurrentApi {
        // Every capture touches psp_clearing, fee_revenue and tax_payable:NL; half also share one merchant account.
        val statuses = parallel(16) { i ->
            client.capture("p_$i", amount = 10_000, merchantId = if (i % 2 == 0) "m_shared" else "m_$i").status
        }

        assertTrue(statuses.all { it == HttpStatusCode.Created }, statuses.toString())
        val report = client.getJson("/v1/reports/balances")
        val mor = report.objects("mor").single()
        assertEquals(16 * 10_000L, mor.long("pspClearing"))
        assertEquals(16 * 500L, mor.long("feeRevenue"))
        assertEquals(8 * 7_764L, report.objects("merchants").single { it.str("merchantId") == "m_shared" }.long("balance"))
        client.assertBooksBalance()
    }

    @Test
    fun `parallel duplicate captures record one payment and one journal entry`() = concurrentApi {
        val statuses = parallel(10) { client.capture("p_dup").status }

        assertEquals(1, statuses.count { it == HttpStatusCode.Created })
        assertEquals(9, statuses.count { it == HttpStatusCode.OK })
        assertEquals(1, client.getJson("/v1/journal?paymentId=p_dup").objects("entries").size)
    }

    @Test
    fun `parallel replays of one refund post it once`() = concurrentApi {
        client.capture("p_1")

        val statuses = parallel(10) { client.refund("p_1", "r_same", amount = 500).status }

        assertEquals(1, statuses.count { it == HttpStatusCode.Created })
        assertEquals(9, statuses.count { it == HttpStatusCode.OK })
        assertEquals(500, client.getJson("/v1/payments/p_1").obj("refunded").long("amount"))
    }
}
