package com.nnomm.flashdeals

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
import java.util.UUID
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DealsAndValidationTest {
    private lateinit var app: TwoInstances
    private val admin = Tokens.admin()

    @BeforeAll fun start() { app = TwoInstances() }
    @AfterAll fun stop() = app.close()
    @BeforeEach fun clean() { TestPostgres.reset() }

    private fun dealBody(
        title: String = "\"Chicken biryani box\"",
        price: String = "9900",
        stock: String = "50",
        starts: String = "\"2026-10-20T12:00:00Z\"",
        ends: String = "\"2026-10-20T14:00:00Z\"",
    ) = """{"title":$title,"price_paise":$price,"stock_total":$stock,"starts_at":$starts,"ends_at":$ends}"""

    @Test
    fun `create and fetch a deal`() = runBlocking {
        val created = app.send("POST", "/v1/deals", dealBody(), admin)
        assertEquals(201, created.status)
        assertEquals("Chicken biryani box", created.field("title"))
        assertEquals("9900", created.field("price_paise"))
        assertEquals("50", created.field("stock_available"))
        assertEquals("2026-10-20T12:00:00Z", created.field("starts_at"))

        val fetched = app.send("GET", "/v1/deals/${created.field("id")}", token = Tokens.customer(), instance = 1)
        assertEquals(200, fetched.status)
        assertEquals(created.body, fetched.body)
    }

    @Test
    fun `invalid deal fields are rejected with VALIDATION_FAILED`() = runBlocking {
        val invalid = listOf(
            dealBody(title = "\"\""),
            dealBody(title = "\"" + "x".repeat(101) + "\""),
            dealBody(title = "null"),
            dealBody(price = "0"),
            dealBody(price = "1000001"),
            dealBody(price = "99.5"),
            dealBody(price = "\"9900\""),
            dealBody(stock = "0"),
            dealBody(stock = "10001"),
            dealBody(starts = "\"yesterday\""),
            dealBody(starts = "\"2026-10-20T14:00:00Z\""), // starts == ends
            dealBody(starts = "\"2026-10-20T15:00:00Z\""), // starts after ends
            "{}",
            "not json",
            "",
        )
        for (body in invalid) {
            val resp = app.send("POST", "/v1/deals", body, admin)
            assertEquals(400, resp.status, body)
            assertEquals("VALIDATION_FAILED", resp.errorCode, body)
        }
        assertEquals(201, app.send("POST", "/v1/deals", dealBody(title = "\"" + "x".repeat(100) + "\"", price = "1000000", stock = "10000"), admin).status)
    }

    @Test
    fun `invalid claim quantities are rejected`() = runBlocking {
        val dealId = app.createDeal(admin)
        for (q in listOf("0", "4", "-1", "1.5", "\"2\"", "null")) {
            val resp = app.send("POST", "/v1/deals/$dealId/claims", """{"quantity":$q}""", Tokens.customer(), mapOf("Idempotency-Key" to "k"))
            assertEquals(400, resp.status, q)
            assertEquals("VALIDATION_FAILED", resp.errorCode, q)
        }
    }

    @Test
    fun `bodies over 16 KB are rejected with 413`() = runBlocking {
        val huge = """{"title":"${"x".repeat(17_000)}"}"""
        val resp = app.send("POST", "/v1/deals", huge, admin)
        assertEquals(413, resp.status)
        assertEquals("PAYLOAD_TOO_LARGE", resp.errorCode)
        assertEquals(413, app.webhook(huge).status)
    }

    @Test
    fun `claims only succeed while the deal is live`() = runBlocking {
        val now = Instant.now()
        val future = app.createDeal(admin, startsAt = now.plusSeconds(3600), endsAt = now.plusSeconds(7200))
        val ended = app.createDeal(admin, startsAt = now.minusSeconds(7200), endsAt = now.minusSeconds(1))

        for (dealId in listOf(future, ended)) {
            val resp = app.claim(Tokens.customer(), dealId)
            assertEquals(409, resp.status)
            assertEquals("DEAL_NOT_LIVE", resp.errorCode)
        }
    }

    @Test
    fun `unknown deals and claims are 404 with the standard error shape`() = runBlocking {
        val customer = Tokens.customer()
        for (path in listOf("/v1/deals/${UUID.randomUUID()}", "/v1/deals/not-a-uuid", "/v1/claims/${UUID.randomUUID()}", "/v1/nope")) {
            val resp = app.send("GET", path, token = customer)
            assertEquals(404, resp.status, path)
            assertEquals("NOT_FOUND", resp.errorCode, path)
        }
    }

    @Test
    fun `a claim response has the documented shape and amount`() = runBlocking {
        val dealId = app.createDeal(admin, price = 9900)
        val claim = app.claim(Tokens.customer(), dealId, quantity = 2)
        assertEquals(201, claim.status)
        assertEquals("19800", claim.field("amount_paise"))
        assertEquals("RESERVED", claim.field("status"))
        assertEquals(dealId, claim.field("deal_id"))
        Instant.parse(claim.field("expires_at"))
    }
}
