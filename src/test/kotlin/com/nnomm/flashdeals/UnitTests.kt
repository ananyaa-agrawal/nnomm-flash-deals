package com.nnomm.flashdeals

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.Payload
import com.nnomm.flashdeals.auth.Role
import com.nnomm.flashdeals.auth.toAuthUser
import com.nnomm.flashdeals.payments.WebhookSignature
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookSignatureTest {
    private val signer = WebhookSignature("secret")
    private val body = """{"event_id":"e1"}""".toByteArray()
    private val now = Instant.parse("2026-10-20T12:00:00Z")

    @Test
    fun `accepts a signature made over t dot raw body`() {
        assertTrue(signer.isValid(signer.sign(now.epochSecond, body), body, now))
    }

    @Test
    fun `accepts upper-case hex and an extra unknown scheme`() {
        val header = signer.sign(now.epochSecond, body).uppercase().replace("T=", "t=").replace("V1=", "v1=")
        assertTrue(signer.isValid("$header,v0=abc", body, now))
    }

    @Test
    fun `a whitespace change in the body breaks the signature`() {
        val header = signer.sign(now.epochSecond, body)
        assertFalse(signer.isValid(header, """{"event_id": "e1"}""".toByteArray(), now))
    }

    @Test
    fun `timestamps more than 300 seconds away are rejected both ways`() {
        assertTrue(signer.isValid(signer.sign(now.epochSecond - 300, body), body, now))
        assertFalse(signer.isValid(signer.sign(now.epochSecond - 301, body), body, now))
        assertFalse(signer.isValid(signer.sign(now.epochSecond + 301, body), body, now))
    }

    @Test
    fun `malformed headers are rejected`() {
        val sig = signer.sign(now.epochSecond, body).substringAfter("v1=")
        for (header in listOf(null, "", "t=", "v1=$sig", "t=abc,v1=$sig", "t=${now.epochSecond},v1=abc", "t=1,t=2,v1=$sig", "garbage")) {
            assertFalse(signer.isValid(header, body, now), "header '$header'")
        }
    }
}

class TokenClaimsTest {
    private fun payload(sub: String?, role: String?, restaurantId: String? = null, exp: Date? = Date()): Payload {
        fun claim(value: String?) = mockk<Claim> { every { asString() } returns value }
        return mockk {
            every { subject } returns sub
            every { expiresAt } returns exp
            every { getClaim("role") } returns claim(role)
            every { getClaim("restaurant_id") } returns claim(restaurantId)
        }
    }

    @Test
    fun `maps a customer token`() {
        val id = UUID.randomUUID()
        val user = toAuthUser(payload(id.toString(), "customer"))!!
        assertEquals(id, user.userId)
        assertEquals(Role.CUSTOMER, user.role)
        assertNull(user.restaurantId)
    }

    @Test
    fun `ignores restaurant_id on a customer token`() {
        assertNull(toAuthUser(payload(UUID.randomUUID().toString(), "customer", UUID.randomUUID().toString()))!!.restaurantId)
    }

    @Test
    fun `rejects bad subjects, roles, missing exp and admins without a restaurant`() {
        assertNull(toAuthUser(payload("not-a-uuid", "customer")))
        assertNull(toAuthUser(payload(null, "customer")))
        assertNull(toAuthUser(payload(UUID.randomUUID().toString(), "admin")))
        assertNull(toAuthUser(payload(UUID.randomUUID().toString(), "customer", exp = null)))
        assertNull(toAuthUser(payload(UUID.randomUUID().toString(), "restaurant_admin")))
        assertNull(toAuthUser(payload(UUID.randomUUID().toString(), "restaurant_admin", "nope")))
    }
}

class AppConfigTest {
    @Test
    fun `accepts libpq-style database urls`() {
        assertEquals("jdbc:postgresql://db:5432/x", AppConfig.toJdbcUrl("postgres://db:5432/x"))
        assertEquals("jdbc:postgresql://db:5432/x", AppConfig.toJdbcUrl("postgresql://db:5432/x"))
        assertEquals("jdbc:postgresql://db:5432/x", AppConfig.toJdbcUrl("jdbc:postgresql://db:5432/x"))
    }

    @Test
    fun `defaults the claim TTL to 600 seconds`() {
        val env = mapOf(
            "DATABASE_URL" to "jdbc:postgresql://db/x", "DATABASE_USER" to "u", "DATABASE_PASSWORD" to "p",
            "JWT_SECRET" to "j", "WEBHOOK_SECRET" to "w",
        )
        assertEquals(600, AppConfig.fromEnv(env).claimTtlSeconds)
        assertEquals(5, AppConfig.fromEnv(env + ("CLAIM_TTL_SECONDS" to "5")).claimTtlSeconds)
    }
}
