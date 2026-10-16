package io.github.refined.morledger.api

import io.github.refined.morledger.ledger.UnsupportedLedgerOperation
import io.github.refined.morledger.payments.ConflictException
import io.github.refined.morledger.payments.DomainException
import io.github.refined.morledger.payments.NotFoundException
import io.github.refined.morledger.payments.UnprocessableException
import io.github.refined.morledger.payments.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ErrorResponse(val error: String, val message: String)

private val log = LoggerFactory.getLogger("io.github.refined.morledger.api.Errors")

fun DomainException.status(): HttpStatusCode = when (this) {
    is ValidationException -> HttpStatusCode.BadRequest
    is NotFoundException -> HttpStatusCode.NotFound
    is ConflictException -> HttpStatusCode.Conflict
    is UnprocessableException -> HttpStatusCode.UnprocessableEntity
}

fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<DomainException> { call, e ->
            call.respond(e.status(), ErrorResponse(e.code, e.message ?: e.code))
        }
        exception<UnsupportedLedgerOperation> { call, e ->
            call.respond(HttpStatusCode.NotImplemented, ErrorResponse("not_supported_by_ledger_engine", e.message ?: "not supported"))
        }
        exception<BadRequestException> { call, e ->
            val detail = generateSequence<Throwable>(e) { it.cause }.last().message ?: "malformed request"
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", detail.lineSequence().first()))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error", "Unexpected error"))
        }
    }
}
