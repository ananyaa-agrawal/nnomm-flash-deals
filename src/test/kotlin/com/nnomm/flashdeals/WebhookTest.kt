package com.nnomm.flashdeals

import com.nnomm.flashdeals.support.Db
import com.nnomm.flashdeals.support.TestPostgres
import com.nnomm.flashdeals.support.Tokens
import com.nnomm.flashdeals.support.TwoInstances
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookTest {
    private lateinit var app: TwoInstances
    private val admin = Tokens.admin()

    // The expiry job is effectively off here, so each test decides exactly
    // when a claim lapses by moving expires_at in the database.
    @BeforeAll fun start() { app = TwoInstances(expiryIntervalMillis = 3_600_000) }
    @AfterAll fun stop() = app.close()
    @BeforeEach fun clean() { TestPostgres.reset() }

    private fun event(claimId: String, amount: Long, type: String = "payment.succeeded", eventId: String = "evt_" + UUID.randomUUID()) =
        """{"event_id":"$eventId","type":"$type","claim_id":"$claimId","amount_paise":$amount,"occurred_at":"${Instant.now()}"}"""

    /** Creates a deal (stock 10, 9900 paise) and a 2-unit claim; returns dealId to claimId. */
    private suspend fun reservedClaim(): Pair<String, String> {
        val dealId = app.createDeal(admin, stock = 10, price = 9900)
        val claim = app.claim(Tokens.customer(), dealId, quantity = 2)
        assertEquals(201, claim.status)
        return dealId to claim.field("id")
    }

    private fun lapse(claimId: String) =
        Db.execute("UPDATE claims SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", claimId)

    @Test
    fun `bad signatures are rejected with 401 and change nothing`() = runBlocking {
        val (_, claimId) = reservedClaim()
        val body = event(claimId, 19800)
        val now = Instant.now()
        val wrongSecret = com.nnomm.flashdeals.payments.WebhookSignature("not-the-secret").sign(now.epochSecond, body.toByteArray())
        val validForOtherBody = app.sign(body.replace("19800", "1"))

        val attempts = mapOf(
            "missing" to null,
            "empty" to "",
            "garbage" to "nonsense",
            "no v1" to "t=${now.epochSecond}",
            "non-hex" to "t=${now.epochSecond},v1=" + "z".repeat(64),
            "wrong secret" to wrongSecret,
            "signature for a different body" to validForOtherBody,
            "stale timestamp" to app.sign(body, now.minusSeconds(301)),
            "future timestamp" to app.sign(body, now.plusSeconds(301)),
        )
        for ((name, signature) in attempts) {
            val resp = app.webhook(body, signature)
            assertEquals(401, resp.status, name)
            assertEquals("UNAUTHENTICATED", resp.errorCode, name)
        }
        assertEquals("RESERVED", Db.status(claimId))
        assertEquals(200, app.webhook(body, app.sign(body, now.minusSeconds(290))).status, "within tolerance")
    }

    @Test
    fun `payment in time with the right amount marks the claim PAID and keeps its units`() = runBlocking {
        val (dealId, claimId) = reservedClaim()

        val resp = app.webhook(event(claimId, 19800))

        assertEquals(200, resp.status)
        assertEquals("PAID", Db.status(claimId))
        assertEquals(8, Db.stockAvailable(dealId))
    }

    @Test
    fun `the same event delivered 20 times concurrently is applied exactly once`() = runBlocking {
        val (dealId, claimId) = reservedClaim()
        val body = event(claimId, 19800, eventId = "evt_dup")

        val responses = (0 until 20).map { i -> async { app.webhook(body, instance = i) } }.awaitAll()

        assertTrue(responses.all { it.status == 200 }, responses.toString())
        assertEquals(1, responses.count { it.field("outcome") == "MARKED_PAID" })
        assertEquals(19, responses.count { it.field("outcome") == "DUPLICATE" })
        assertEquals("PAID", Db.status(claimId))
        assertEquals(8, Db.stockAvailable(dealId))
    }

    @Test
    fun `out of order - a failure arriving after success does not undo the payment`() = runBlocking {
        val (_, claimId) = reservedClaim()

        app.webhook(event(claimId, 19800, "payment.succeeded"))
        val late = app.webhook(event(claimId, 19800, "payment.failed"))

        assertEquals(200, late.status)
        assertEquals("PAID", Db.status(claimId))
    }

    @Test
    fun `a failed payment changes nothing and the customer can pay again before expiry`() = runBlocking {
        val (_, claimId) = reservedClaim()

        assertEquals(200, app.webhook(event(claimId, 19800, "payment.failed")).status)
        assertEquals("RESERVED", Db.status(claimId))
        app.webhook(event(claimId, 19800, "payment.succeeded"))
        assertEquals("PAID", Db.status(claimId))
    }

    @Test
    fun `the wrong amount in time goes to NEEDS_REVIEW and keeps the units`() = runBlocking {
        val (dealId, claimId) = reservedClaim()

        app.webhook(event(claimId, 100))

        assertEquals("NEEDS_REVIEW", Db.status(claimId))
        assertEquals(8, Db.stockAvailable(dealId))
    }

    @Test
    fun `payment after expiry on a still-RESERVED row goes to REFUND_PENDING and returns the units`() = runBlocking {
        val (dealId, claimId) = reservedClaim()
        lapse(claimId) // expired, but no sweep has run yet

        app.webhook(event(claimId, 19800))

        assertEquals("REFUND_PENDING", Db.status(claimId))
        assertEquals(10, Db.stockAvailable(dealId))
    }

    @Test
    fun `payment after the expiry job ran goes to REFUND_PENDING without returning units twice`() = runBlocking {
        val (dealId, claimId) = reservedClaim()
        lapse(claimId)
        // A claim on the same deal releases lapsed units, as the job would.
        app.claim(Tokens.customer(), dealId, 1)
        assertEquals("EXPIRED", Db.status(claimId))
        assertEquals(9, Db.stockAvailable(dealId))

        app.webhook(event(claimId, 19800))

        assertEquals("REFUND_PENDING", Db.status(claimId))
        assertEquals(9, Db.stockAvailable(dealId))
        assertEquals(10, Db.stockAvailable(dealId) + Db.unitsHeld(dealId))
    }

    @Test
    fun `a second successful payment on a PAID claim leaves it PAID and is flagged`() = runBlocking {
        val (dealId, claimId) = reservedClaim()
        app.webhook(event(claimId, 19800))

        val second = app.webhook(event(claimId, 19800))

        assertEquals(200, second.status)
        assertEquals("EXTRA_PAYMENT", second.field("outcome"))
        assertEquals("PAID", Db.status(claimId))
        assertEquals(8, Db.stockAvailable(dealId))
    }

    @Test
    fun `an unknown claim id is acknowledged so the provider stops retrying`() = runBlocking {
        val resp = app.webhook(event(UUID.randomUUID().toString(), 100))
        assertEquals(200, resp.status)
        assertEquals("UNKNOWN_CLAIM", resp.field("outcome"))
    }

    @Test
    fun `a correctly signed but malformed body is a 400`() = runBlocking {
        assertEquals(400, app.webhook("""{"event_id":"e1","type":"payment.succeeded"}""").status)
        assertEquals(400, app.webhook("not json").status)
    }
}
