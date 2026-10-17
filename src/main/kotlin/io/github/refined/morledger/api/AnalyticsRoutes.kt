package io.github.refined.morledger.api

import io.github.refined.morledger.payments.validateCurrency
import io.github.refined.morledger.payments.ValidationException
import io.github.refined.morledger.reports.Analytics
import io.github.refined.morledger.reports.CurrencySummary
import io.github.refined.morledger.reports.Flow
import io.github.refined.morledger.reports.JurisdictionSummary
import io.github.refined.morledger.reports.MerchantSummary
import io.github.refined.morledger.reports.TimePoint
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeParseException


@Serializable
data class FlowDto(val count: Long, val gross: Long, val tax: Long, val fee: Long, val merchant: Long)

@Serializable
data class JurisdictionDto(val jurisdiction: String, val captured: FlowDto, val refunded: FlowDto, val netTax: Long)

@Serializable
data class CurrencySummaryDto(
    val currency: String,
    val merchants: Long,
    val captured: FlowDto,
    val refunded: FlowDto,
    val net: FlowDto,
    val refundRate: Double,
    val byJurisdiction: List<JurisdictionDto>,
)

@Serializable
data class SummaryResponse(val from: String?, val to: String?, val currencies: List<CurrencySummaryDto>)

@Serializable
data class TimePointDto(val start: String, val captured: FlowDto, val refunded: FlowDto, val netGross: Long, val merchantBalance: Long)

@Serializable
data class TimeseriesResponse(val currency: String, val merchantId: String?, val bucket: String, val points: List<TimePointDto>)

@Serializable
data class MerchantSummaryDto(
    val merchantId: String,
    val currency: String,
    val captured: FlowDto,
    val refunded: FlowDto,
    val net: FlowDto,
    val refundRate: Double,
    val firstPaymentAt: String,
    val lastPaymentAt: String,
    val balance: Long,
    val ledgerBalance: Long?,
    val reconciled: Boolean,
)

@Serializable
data class MerchantPageResponse(val total: Long, val merchants: List<MerchantSummaryDto>)

@Serializable
data class MerchantJurisdictionDto(val currency: String, val jurisdiction: String, val captured: FlowDto, val refunded: FlowDto)

@Serializable
data class MerchantDetailResponse(val merchantId: String, val currencies: List<MerchantSummaryDto>, val jurisdictions: List<MerchantJurisdictionDto>)

fun Flow.toDto() = FlowDto(count, gross, tax, fee, merchant)

private fun rate(part: Long, whole: Long): Double =
    if (whole == 0L) 0.0 else BigDecimal(part).divide(BigDecimal(whole), 4, RoundingMode.HALF_UP).toDouble()

private fun JurisdictionSummary.toDto() = JurisdictionDto(jurisdiction, captured.toDto(), refunded.toDto(), captured.tax - refunded.tax)

private fun CurrencySummary.toDto() = CurrencySummaryDto(
    currency = currency,
    merchants = merchants,
    captured = captured.toDto(),
    refunded = refunded.toDto(),
    net = net.toDto(),
    refundRate = rate(refunded.gross, captured.gross),
    byJurisdiction = byJurisdiction.map { it.toDto() },
)

private fun TimePoint.toDto() =
    TimePointDto(start.toString(), captured.toDto(), refunded.toDto(), captured.gross - refunded.gross, merchantBalance)

private fun MerchantSummary.toDto() = MerchantSummaryDto(
    merchantId = merchantId,
    currency = currency,
    captured = captured.toDto(),
    refunded = refunded.toDto(),
    net = (captured - refunded).toDto(),
    refundRate = rate(refunded.gross, captured.gross),
    firstPaymentAt = firstPaymentAt.toString(),
    lastPaymentAt = lastPaymentAt.toString(),
    balance = balanceFromRecords,
    ledgerBalance = ledgerBalance,
    reconciled = reconciled,
)

fun Route.analyticsRoutes(analytics: Analytics) {
    route("/analytics") {
        get("/summary") {
            val q = call.request.queryParameters
            val currency = q["currency"]?.let { validateCurrency(it) }
            val from = instant("from", q["from"])
            val to = instant("to", q["to"])
            val summary = blocking { analytics.summary(currency, from, to) }
            call.respond(SummaryResponse(from?.toString(), to?.toString(), summary.map { it.toDto() }))
        }

        get("/timeseries") {
            val q = call.request.queryParameters
            val currency = validateCurrency(q["currency"] ?: throw ValidationException("currency is required"))
            val bucket = Analytics.parseBucket(q["bucket"])
            val merchantId = q["merchantId"]
            val from = instant("from", q["from"])
            val to = instant("to", q["to"])
            val points = blocking { analytics.timeseries(currency, merchantId, bucket, from, to) }
            call.respond(TimeseriesResponse(currency, merchantId, bucket.name.lowercase(), points.map { it.toDto() }))
        }

        get("/merchants") {
            val q = call.request.queryParameters
            val currency = q["currency"]?.let { validateCurrency(it) }
            val search = q["q"]?.takeIf { it.isNotBlank() }
            val sort = Analytics.parseSort(q["sort"])
            val limit = intParam("limit", q["limit"], default = 50, range = 1..500)
            val offset = intParam("offset", q["offset"], default = 0, range = 0..Int.MAX_VALUE)
            val page = blocking { analytics.merchants(currency, search, sort, q["order"] != "asc", limit, offset) }
            call.respond(MerchantPageResponse(page.total, page.rows.map { it.toDto() }))
        }

        get("/merchants/{merchantId}") {
            val merchantId = call.parameters["merchantId"]!!
            val detail = blocking { analytics.merchant(merchantId) }
            call.respond(
                MerchantDetailResponse(
                    merchantId = detail.merchantId,
                    currencies = detail.currencies.map { it.toDto() },
                    jurisdictions = detail.byJurisdiction.flatMap { (currency, list) ->
                        list.map { MerchantJurisdictionDto(currency, it.jurisdiction, it.captured.toDto(), it.refunded.toDto()) }
                    },
                ),
            )
        }
    }
}

internal fun instant(field: String, value: String?): Instant? = value?.let {
    try {
        Instant.parse(it)
    } catch (e: DateTimeParseException) {
        throw ValidationException("$field must be an ISO-8601 UTC timestamp, e.g. 2026-10-14T10:00:00Z")
    }
}

internal fun intParam(field: String, value: String?, default: Int, range: IntRange): Int = value?.let {
    it.toIntOrNull()?.takeIf { n -> n in range } ?: throw ValidationException("$field must be an integer in $range")
} ?: default
