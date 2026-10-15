package com.nnomm.flashdeals.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

const val MAX_BODY_BYTES = 16 * 1024

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

/**
 * Reads the raw request body, refusing anything over 16 KB with 413 — whether
 * the client declared a large Content-Length or streamed a chunked body.
 * The webhook needs these exact bytes to verify the signature.
 */
suspend fun ApplicationCall.receiveLimitedBytes(): ByteArray {
    val declared = request.contentLength()
    if (declared != null && declared > MAX_BODY_BYTES) tooLarge()
    val bytes = receiveChannel().readRemaining((MAX_BODY_BYTES + 1).toLong()).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) tooLarge()
    return bytes
}

private fun tooLarge(): Nothing =
    throw ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Request body must be at most $MAX_BODY_BYTES bytes")

fun <T> decodeBody(serializer: KSerializer<T>, bytes: ByteArray): T {
    if (bytes.isEmpty()) validation("Request body is required")
    return try {
        json.decodeFromString(serializer, bytes.decodeToString())
    } catch (e: SerializationException) {
        validation("Malformed JSON body")
    } catch (e: IllegalArgumentException) {
        validation("Malformed JSON body")
    }
}

suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) =
    respondText(body, ContentType.Application.Json, status)

fun parseUuidOrNull(value: String?): UUID? =
    value?.takeIf { it.length == 36 }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

fun parseInstant(field: String, value: String?): Instant {
    if (value == null) validation("$field is required")
    return try {
        OffsetDateTime.parse(value).toInstant()
    } catch (e: DateTimeParseException) {
        validation("$field must be an ISO 8601 timestamp with an offset, e.g. 2026-10-20T12:00:00Z")
    }
}

/** All timestamps go out as ISO 8601 UTC with millisecond precision. */
fun Instant.iso(): String = truncatedTo(ChronoUnit.MILLIS).toString()

/**
 * Accepts only a bare JSON integer. The default Long decoder also accepts
 * "9900" (a string), which would let sloppy clients send money as text.
 */
object StrictLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor(
        "StrictLong", PrimitiveKind.LONG,
    )

    override fun deserialize(decoder: Decoder): Long {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        if (element !is JsonPrimitive || element.isString) {
            throw SerializationException("expected an integer")
        }
        return element.content.toLongOrNull() ?: throw SerializationException("expected an integer")
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}
