package io.github.refined.morledger.api

import io.github.refined.morledger.TestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsApiTest {
    @BeforeEach
    fun reset() = TestDatabase.reset()

    private suspend fun HttpClient.capture(id: String, merchant: String, amount: Long, day: Int, currency: String = "EUR", jurisdiction: String = "NL", rate: String = "0.21") {
        val response = capture(id, amount, merchant, currency, rate, jurisdiction, capturedAt = "2026-09-${"%02d".format(day)}T10:00:00Z")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.refund(paymentId: String, refundId: String, amount: Long?, day: Int) {
        val response = refund(paymentId, refundId, amount, refundedAt = "2026-09-${"%02d".format(day)}T12:00:00Z")
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

    @Test
    fun `summary totals per currency, with jurisdictions`() = api {
        client.seed()

        val eur = client.getJson("/v1/analytics/summary").objects("currencies").single { it.str("currency") == "EUR" }
        assertEquals(1, eur.long("merchants"))
        assertEquals(36_100, eur.obj("captured").long("gross"))
        assertEquals(6_100, eur.obj("captured").long("tax"))
        assertEquals(6_050, eur.obj("refunded").long("gross"))
        assertEquals(30_050, eur.obj("net").long("gross"))
        val nl = eur.objects("byJurisdiction").single { it.str("jurisdiction") == "NL" }
        assertEquals(4_200 - 1_050, nl.long("netTax"))

        val filtered = client.getJson("/v1/analytics/summary?currency=USD&to=2026-09-04T00:00:00Z").objects("currencies")
        assertEquals(1, filtered.size)
        assertEquals(0, filtered.single().obj("refunded").long("gross")) // the USD refund is on day 5
    }

    @Test
    fun `timeseries fills empty days and carries the merchant's running balance`() = api {
        client.seed()

        val points = client.getJson("/v1/analytics/timeseries?currency=EUR&merchantId=acme").objects("points")
        assertEquals(listOf("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04"), points.map { it.str("start") })
        assertEquals(listOf(18_790L, 18_790L, 28_195L, 23_195L), points.map { it.long("merchantBalance") })
        assertEquals(0, points[1].obj("captured").long("gross"))

        val fromDay3 = client.getJson("/v1/analytics/timeseries?currency=EUR&merchantId=acme&from=2026-09-03T00:00:00Z")
            .objects("points")
        assertEquals(28_195, fromDay3.first().long("merchantBalance")) // opening balance before the window included

        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/analytics/timeseries").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/analytics/timeseries?currency=EUR&bucket=year").status)
    }

    @Test
    fun `merchant breakdown is reconciled with the ledger and sortable`() = api {
        client.seed()

        val page = client.getJson("/v1/analytics/merchants?sort=refundRate")
        assertEquals(2, page.long("total"))
        val rows = page.objects("merchants")
        assertEquals(listOf("globex", "acme"), rows.map { it.str("merchantId") })
        assertTrue(rows.all { it.str("reconciled") == "true" })

        val globex = rows.first()
        assertEquals(1.0, globex.str("refundRate").toDouble())
        assertEquals(-500, globex.long("balance")) // fee kept on refund: the merchant owes it
        assertEquals(-500, globex.long("ledgerBalance"))

        val acme = rows.last()
        assertEquals(3, acme.obj("captured").long("count"))
        assertEquals(23_195, acme.long("balance"))

        val searched = client.getJson("/v1/analytics/merchants?q=glo")
        assertEquals(1, searched.long("total"))
    }

    @Test
    fun `merchant detail breaks down by jurisdiction`() = api {
        client.seed()

        val detail = client.getJson("/v1/analytics/merchants/acme")
        val jurisdictions = detail.objects("jurisdictions")
        assertEquals(setOf("NL", "DE"), jurisdictions.map { it.str("jurisdiction") }.toSet())
        assertEquals(2, jurisdictions.single { it.str("jurisdiction") == "NL" }.obj("captured").long("count"))

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/analytics/merchants/nobody").status)
    }

    @Test
    fun `payments can be filtered by merchant and status, with a total for paging`() = api {
        client.seed()

        val acme = client.getJson("/v1/payments?merchantId=acme&limit=2")
        assertEquals(3, acme.long("total"))
        assertEquals(listOf("p3", "p2"), acme.objects("payments").map { it.str("paymentId") }) // latest capture first

        val second = client.getJson("/v1/payments?merchantId=acme&limit=2&offset=2")
        assertEquals(listOf("p1"), second.objects("payments").map { it.str("paymentId") })

        val refunded = client.getJson("/v1/payments?status=REFUNDED")
        assertEquals(listOf("p4"), refunded.objects("payments").map { it.str("paymentId") })
        val partial = client.getJson("/v1/payments?status=PARTIALLY_REFUNDED")
        assertEquals(listOf("p1"), partial.objects("payments").map { it.str("paymentId") })

        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/payments?status=LOST").status)
    }

    @Test
    fun `ledger accounts list every account and verify confirms stored balances`() = api {
        client.seed()

        val accounts = client.getJson("/v1/ledger/accounts?currency=EUR").objects("accounts")
        val acme = accounts.single { it.str("code") == "merchant_payable:acme" }
        assertEquals(23_195, acme.long("balance"))
        assertTrue(accounts.all { it.str("currency") == "EUR" })

        client.assertBooksBalance()
        assertTrue(client.get("/health").bodyAsText().contains("postgres"))
    }

    @Test
    fun `journal can be filtered by merchant and paged`() = api {
        client.seed()

        val page1 = client.getJson("/v1/journal?merchantId=acme&limit=2").objects("entries")
        assertEquals(2, page1.size)
        val before = page1.last().long("id")
        val page2 = client.getJson("/v1/journal?merchantId=acme&before=$before").objects("entries")
        assertEquals(2, page2.size) // acme has 4 entries: 3 captures + 1 refund
        assertTrue(page2.all { it.long("id") < before })
    }
}
