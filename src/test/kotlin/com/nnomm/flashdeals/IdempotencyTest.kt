package com.nnomm.flashdeals

import com.nnomm.flashdeals.support.Db
import com.nnomm.flashdeals.support.TestPostgres
import com.nnomm.flashdeals.support.Tokens
import com.nnomm.flashdeals.support.TwoInstances
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyTest {
    private lateinit var app: TwoInstances
    private val admin = Tokens.admin()

    @BeforeAll fun start() { app = TwoInstances() }
    @AfterAll fun stop() = app.close()
    @BeforeEach fun clean() { TestPostgres.reset() }

    @Test
    fun `a retry on the other instance replays the original response`() = runBlocking {
        val dealId = app.createDeal(admin)
        val customer = Tokens.customer()

        val first = app.claim(customer, dealId, 2, key = "k1", instance = 0)
        val retry = app.claim(customer, dealId, 2, key = "k1", instance = 1)

        assertEquals(201, first.status)
        assertNull(first.header("Idempotent-Replayed"))
        assertEquals(201, retry.status)
        assertEquals("true", retry.header("Idempotent-Replayed"))
        assertEquals(first.body, retry.body)
        assertEquals(1, Db.claimCount(dealId))
    }

    @Test
    fun `same key with a different quantity or deal is rejected with 422`() = runBlocking {
        val dealId = app.createDeal(admin)
        val otherDeal = app.createDeal(admin)
        val customer = Tokens.customer()
        app.claim(customer, dealId, 1, key = "k1")

        val differentQuantity = app.claim(customer, dealId, 2, key = "k1")
        val differentDeal = app.claim(customer, otherDeal, 1, key = "k1")

        assertEquals(422, differentQuantity.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", differentQuantity.errorCode)
        assertEquals(422, differentDeal.status)
        assertEquals(0, Db.claimCount(otherDeal))
    }

    @Test
    fun `keys are scoped to the customer`() = runBlocking {
        val dealId = app.createDeal(admin)

        val a = app.claim(Tokens.customer(), dealId, 1, key = "shared")
        val b = app.claim(Tokens.customer(), dealId, 1, key = "shared")

        assertEquals(201, a.status)
        assertEquals(201, b.status)
        assertNull(b.header("Idempotent-Replayed"))
        assertEquals(2, Db.claimCount(dealId))
    }

    @Test
    fun `a stored SOLD_OUT stays SOLD_OUT even after units free up`() = runBlocking {
        val dealId = app.createDeal(admin, stock = 1)
        val winner = app.claim(Tokens.customer(), dealId, 1)
        val loser = Tokens.customer()
        assertEquals("SOLD_OUT", app.claim(loser, dealId, 1, key = "late").errorCode)

        // Free the unit: expire the winner's claim and let the release run.
        Db.execute("UPDATE claims SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", winner.field("id"))
        Thread.sleep(800)
        assertEquals(1, Db.stockAvailable(dealId))

        val replay = app.claim(loser, dealId, 1, key = "late", instance = 1)
        assertEquals(409, replay.status)
        assertEquals("SOLD_OUT", replay.errorCode)
        assertEquals("true", replay.header("Idempotent-Replayed"))

        assertEquals(201, app.claim(loser, dealId, 1, key = "new-key").status)
    }

    @Test
    fun `stored conflicts replay too - ALREADY_CLAIMED and DEAL_NOT_LIVE`() = runBlocking {
        val dealId = app.createDeal(admin)
        val customer = Tokens.customer()
        app.claim(customer, dealId, 1, key = "a")
        assertEquals("ALREADY_CLAIMED", app.claim(customer, dealId, 1, key = "b").errorCode)
        val replay = app.claim(customer, dealId, 1, key = "b")
        assertEquals("ALREADY_CLAIMED", replay.errorCode)
        assertEquals("true", replay.header("Idempotent-Replayed"))
    }

    @Test
    fun `missing or malformed keys are rejected with 400 and nothing is stored`() = runBlocking {
        val dealId = app.createDeal(admin)
        val customer = Tokens.customer()

        val missing = app.send("POST", "/v1/deals/$dealId/claims", """{"quantity":1}""", customer)
        assertEquals(400, missing.status)
        assertEquals("IDEMPOTENCY_KEY_INVALID", missing.errorCode)
        for (bad in listOf("", "has space", "a".repeat(65), "bad!char", "dot.key", "slash/key")) {
            val resp = app.send("POST", "/v1/deals/$dealId/claims", """{"quantity":1}""", customer, mapOf("Idempotency-Key" to bad))
            assertEquals(400, resp.status, "key '$bad'")
            assertEquals("IDEMPOTENCY_KEY_INVALID", resp.errorCode)
        }
        assertEquals(201, app.claim(customer, dealId, 1, key = "a".repeat(64)).status)
    }

    @Test
    fun `requests rejected with 400 or 404 are not stored`() = runBlocking {
        val dealId = app.createDeal(admin)
        val customer = Tokens.customer()

        assertEquals(400, app.claim(customer, dealId, quantity = 4, key = "k").status)
        assertEquals(404, app.claim(customer, UUID.randomUUID().toString(), quantity = 1, key = "k").status)
        // The same key now works for a valid request: nothing was stored above.
        assertEquals(201, app.claim(customer, dealId, quantity = 1, key = "k").status)
    }
}
