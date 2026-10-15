package com.nnomm.flashdeals.payments

import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Verifies `X-Payment-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>` where the
 * signed message is `t + "." + raw body bytes`.
 */
class WebhookSignature(secret: String, private val toleranceSeconds: Long = 300) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC)

    fun isValid(header: String?, rawBody: ByteArray, now: Instant): Boolean {
        val parsed = parse(header) ?: return false
        // Replay protection: a captured request stops working after 5 minutes.
        if (abs(now.epochSecond - parsed.timestamp) > toleranceSeconds) return false
        val expected = hmac(parsed.timestamp, rawBody)
        // Constant-time comparison, so response timing leaks nothing about how
        // many leading bytes of a forged signature were right.
        return parsed.signatures.any { MessageDigest.isEqual(expected, it) }
    }

    fun sign(timestamp: Long, rawBody: ByteArray): String =
        "t=$timestamp,v1=" + hmac(timestamp, rawBody).joinToString("") { "%02x".format(it) }

    private fun hmac(timestamp: Long, rawBody: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(key)
        mac.update("$timestamp.".toByteArray(Charsets.UTF_8))
        return mac.doFinal(rawBody)
    }

    private class Parsed(val timestamp: Long, val signatures: List<ByteArray>)

    private fun parse(header: String?): Parsed? {
        if (header.isNullOrBlank() || header.length > 1024) return null
        var timestamp: Long? = null
        val signatures = mutableListOf<ByteArray>()
        for (part in header.split(',')) {
            val pieces = part.trim().split('=', limit = 2)
            if (pieces.size != 2) return null
            val (name, value) = pieces
            when (name) {
                "t" -> {
                    if (timestamp != null || !value.matches(DIGITS)) return null
                    timestamp = value.toLongOrNull() ?: return null
                }
                "v1" -> signatures += decodeHex(value) ?: return null
                else -> Unit // ignore unknown schemes (e.g. a future v2)
            }
        }
        if (timestamp == null || signatures.isEmpty()) return null
        return Parsed(timestamp, signatures)
    }

    private fun decodeHex(value: String): ByteArray? {
        if (!value.matches(HEX_SHA256)) return null
        return ByteArray(32) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        val DIGITS = Regex("^[0-9]{1,12}$")
        val HEX_SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
