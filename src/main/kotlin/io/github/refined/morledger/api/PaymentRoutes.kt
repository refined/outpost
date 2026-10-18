package io.github.refined.morledger.api

import io.github.refined.morledger.payments.CaptureCommand
import io.github.refined.morledger.payments.PaymentService
import io.github.refined.morledger.payments.PaymentStatus
import io.github.refined.morledger.payments.RefundCommand
import io.github.refined.morledger.payments.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.math.BigDecimal

fun Route.paymentRoutes(payments: PaymentService) {
    post("/payments") {
        val result = payments.capture(call.receive<CaptureRequest>().toCommand())
        call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.value.toDto())
    }

    get("/payments") {
        val q = call.request.queryParameters
        val status = q["status"]?.let { s ->
            PaymentStatus.entries.firstOrNull { it.name == s }
                ?: throw ValidationException("status must be one of ${PaymentStatus.entries.joinToString()}")
        }
        val limit = parseLimit(q["limit"], default = 50)
        val offset = intParam("offset", q["offset"], default = 0, range = 0..Int.MAX_VALUE)
        val page = blocking { payments.page(q["merchantId"], status, limit, offset) }
        call.respond(PaymentsResponse(page.rows.map { it.toDto() }, page.total))
    }

    get("/payments/{paymentId}") {
        val paymentId = call.parameters["paymentId"]!!
        call.respond(blocking { payments.get(paymentId) }.toDto())
    }

    post("/payments/{paymentId}/refunds") {
        val req = call.receive<RefundRequest>()
        val cmd = RefundCommand(
            paymentId = call.parameters["paymentId"]!!,
            refundId = req.refundId,
            amount = req.amount,
            refundedAt = instant("refundedAt", req.refundedAt),
        )
        val result = payments.refund(cmd)
        call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.value.toResponse())
    }
}

private fun CaptureRequest.toCommand() = CaptureCommand(
    paymentId = paymentId,
    merchantId = merchantId,
    amount = amount,
    currency = currency,
    taxRate = parseRate("taxRate", taxRate),
    taxJurisdiction = taxJurisdiction,
    capturedAt = instant("capturedAt", capturedAt),
)

private fun parseRate(field: String, value: String): BigDecimal =
    value.toBigDecimalOrNull() ?: throw ValidationException("$field must be a decimal string, e.g. \"0.21\"")
