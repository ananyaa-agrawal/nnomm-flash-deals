package com.nnomm.flashdeals

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.flashdeals.support.JWT_SECRET
import com.nnomm.flashdeals.support.TestPostgres
import com.nnomm.flashdeals.support.Tokens
import com.nnomm.flashdeals.support.TwoInstances
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthAndVisibilityTest {
    private lateinit var app: TwoInstances

    @BeforeAll fun start() { app = TwoInstances() }
    @AfterAll fun stop() = app.close()
    @BeforeEach fun clean() { TestPostgres.reset() }

    @Test
    fun `customers cannot read other customers' claims`() = runBlocking {
        val dealId = app.createDeal(Tokens.admin())
        val owner = Tokens.customer()
        val claimId = app.claim(owner, dealId).field("id")

        assertEquals(200, app.send("GET", "/v1/claims/$claimId", token = owner).status)
        val other = app.send("GET", "/v1/claims/$claimId", token = Tokens.customer(), instance = 1)
        assertEquals(404, other.status)
        assertEquals("NOT_FOUND", other.errorCode)
    }

    @Test
    fun `admins can read claims on their own restaurant's deals only`() = runBlocking {
        val restaurant = UUID.randomUUID()
        val ownAdmin = Tokens.admin(restaurant)
        val dealId = app.createDeal(ownAdmin)
        val claimId = app.claim(Tokens.customer(), dealId).field("id")

        assertEquals(200, app.send("GET", "/v1/claims/$claimId", token = ownAdmin).status)
        assertEquals(200, app.send("GET", "/v1/claims/$claimId", token = Tokens.admin(restaurant)).status, "another admin of the same restaurant")
        assertEquals(404, app.send("GET", "/v1/claims/$claimId", token = Tokens.admin()).status)
    }

    @Test
    fun `a deal is always created for the admin's own restaurant`() = runBlocking {
        val restaurant = UUID.randomUUID()
        val resp = app.send(
            "POST", "/v1/deals", token = Tokens.admin(restaurant),
            body = """{"title":"x","price_paise":100,"stock_total":5,"starts_at":"2026-10-20T12:00:00Z","ends_at":"2026-10-20T14:00:00Z","restaurant_id":"${UUID.randomUUID()}"}""",
        )
        assertEquals(201, resp.status)
        assertEquals(restaurant.toString(), resp.field("restaurant_id"))
    }

    @Test
    fun `roles - only admins create deals and only customers claim`() = runBlocking {
        val dealId = app.createDeal(Tokens.admin())
        val asCustomer = app.send("POST", "/v1/deals", """{"title":"x"}""", Tokens.customer())
        assertEquals(403, asCustomer.status)
        assertEquals("FORBIDDEN", asCustomer.errorCode)
        assertEquals(403, app.claim(Tokens.admin(), dealId).status)
    }

    @Test
    fun `missing, expired, tampered and wrong-algorithm tokens are rejected`() = runBlocking {
        val dealId = app.createDeal(Tokens.admin())
        val user = UUID.randomUUID().toString()
        val valid = Tokens.customer()
        val (header, payload, signature) = valid.split('.')
        val forgedPayload = b64("""{"sub":"$user","role":"restaurant_admin","restaurant_id":"${UUID.randomUUID()}","exp":${Instant.now().epochSecond + 3600}}""")

        val tokens = mapOf(
            "missing" to null,
            "garbage" to "not.a.jwt",
            "expired" to JWT.create().withSubject(user).withClaim("role", "customer")
                .withExpiresAt(Date.from(Instant.now().minusSeconds(5))).sign(Algorithm.HMAC256(JWT_SECRET)),
            "no exp" to JWT.create().withSubject(user).withClaim("role", "customer").sign(Algorithm.HMAC256(JWT_SECRET)),
            "wrong secret" to JWT.create().withSubject(user).withClaim("role", "customer")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(60))).sign(Algorithm.HMAC256("another-secret")),
            "HS512" to JWT.create().withSubject(user).withClaim("role", "customer")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(60))).sign(Algorithm.HMAC512(JWT_SECRET)),
            "alg none" to b64("""{"alg":"none","typ":"JWT"}""") + "." + payload + ".",
            "tampered payload" to "$header.$forgedPayload.$signature",
            "unknown role" to JWT.create().withSubject(user).withClaim("role", "superuser")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(60))).sign(Algorithm.HMAC256(JWT_SECRET)),
            "admin without restaurant" to JWT.create().withSubject(user).withClaim("role", "restaurant_admin")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(60))).sign(Algorithm.HMAC256(JWT_SECRET)),
        )
        for ((name, token) in tokens) {
            val resp = app.send("GET", "/v1/deals/$dealId", token = token)
            assertEquals(401, resp.status, name)
            assertEquals("UNAUTHENTICATED", resp.errorCode, name)
        }
        assertEquals(200, app.send("GET", "/v1/deals/$dealId", token = valid).status)
    }

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
}
