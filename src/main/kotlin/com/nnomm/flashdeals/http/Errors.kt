package com.nnomm.flashdeals.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

enum class ErrorCode(val status: HttpStatusCode) {
    VALIDATION_FAILED(HttpStatusCode.BadRequest),
    IDEMPOTENCY_KEY_INVALID(HttpStatusCode.BadRequest),
    UNAUTHENTICATED(HttpStatusCode.Unauthorized),
    FORBIDDEN(HttpStatusCode.Forbidden),
    NOT_FOUND(HttpStatusCode.NotFound),
    SOLD_OUT(HttpStatusCode.Conflict),
    ALREADY_CLAIMED(HttpStatusCode.Conflict),
    DEAL_NOT_LIVE(HttpStatusCode.Conflict),
    REQUEST_IN_PROGRESS(HttpStatusCode.Conflict),
    PAYLOAD_TOO_LARGE(HttpStatusCode.PayloadTooLarge),
    IDEMPOTENCY_KEY_REUSED(HttpStatusCode.UnprocessableEntity),
    INTERNAL(HttpStatusCode.InternalServerError),
}

class ApiException(val code: ErrorCode, override val message: String) : RuntimeException(message)

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String)

fun errorBody(code: ErrorCode, message: String) = ErrorBody(ErrorDetail(code.name, message))

fun validation(message: String): Nothing = throw ApiException(ErrorCode.VALIDATION_FAILED, message)
fun notFound(message: String = "Not found"): Nothing = throw ApiException(ErrorCode.NOT_FOUND, message)
fun forbidden(message: String): Nothing = throw ApiException(ErrorCode.FORBIDDEN, message)
fun unauthenticated(message: String): Nothing = throw ApiException(ErrorCode.UNAUTHENTICATED, message)
