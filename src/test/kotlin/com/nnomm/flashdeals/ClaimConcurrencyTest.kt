package com.nnomm.flashdeals

import com.nnomm.flashdeals.support.Db
import com.nnomm.flashdeals.support.Tokens
import com.nnomm.flashdeals.support.TestPostgres
import com.nnomm.flashdeals.support.TwoInstances
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaimConcurrencyTest {
    private lateinit var app: TwoInstances
    private val admin = Tokens.admin()

    @BeforeAll fun start() { app = TwoInstances() }
    @AfterAll fun stop() = app.close()
    @BeforeEach fun clean() { TestPostgres.reset() }

    @Test
    fun `250 concurrent claims across both instances never oversell a 50-unit deal`() = runBlocking {
        val dealId = app.createDeal(admin, stock = 50)

        val responses = (0 until 250).map { i ->
            async { app.claim(Tokens.customer(), dealId, quantity = 1, instance = i) }
        }.awaitAll()

        assertEquals(50, responses.count { it.status == 201 }, "exactly the stock is sold")
        assertTrue(responses.filter { it.status != 201 }.all { it.status == 409 && it.errorCode == "SOLD_OUT" })
        assertEquals(0, Db.stockAvailable(dealId))
        assertEquals(50, Db.unitsHeld(dealId))
        assertEquals(50, Db.claimCount(dealId))
    }

    @Test
    fun `mixed quantities never oversell and stock plus held always equals total`() = runBlocking {
        val dealId = app.createDeal(admin, stock = 50)

        val responses = (0 until 220).map { i ->
            async { app.claim(Tokens.customer(), dealId, quantity = 1 + i % 3, instance = i) }
        }.awaitAll()

        val sold = responses.filter { it.status == 201 }.sumOf { it.field("quantity").toInt() }
        assertTrue(sold <= 50, "sold $sold units of 50")
        assertEquals(50, Db.stockAvailable(dealId) + Db.unitsHeld(dealId))
        assertEquals(sold, Db.unitsHeld(dealId))
        assertTrue(responses.filter { it.status != 201 }.all { it.errorCode == "SOLD_OUT" })
    }

    @Test
    fun `25 concurrent requests with one Idempotency-Key create exactly one claim`() = runBlocking {
        val dealId = app.createDeal(admin, stock = 50)
        val customer = Tokens.customer()
        val key = "retry-" + UUID.randomUUID()

        val responses = (0 until 25).map { i ->
            async { app.claim(customer, dealId, quantity = 2, key = key, instance = i) }
        }.awaitAll()

        assertEquals(1, Db.claimCount(dealId))
        assertTrue(responses.all { it.status == 201 }, "all see the original 201: $responses")
        assertEquals(1, responses.map { it.body }.distinct().size, "every response replays the same body")
        assertEquals(24, responses.count { it.header("Idempotent-Replayed") == "true" })
        assertEquals(48, Db.stockAvailable(dealId))
    }

    @Test
    fun `concurrent claims by one customer with different keys create exactly one claim`() = runBlocking {
        val dealId = app.createDeal(admin, stock = 50)
        val customer = Tokens.customer()

        val responses = (0 until 30).map { i ->
            async { app.claim(customer, dealId, quantity = 1, instance = i) }
        }.awaitAll()

        assertEquals(1, responses.count { it.status == 201 })
        assertTrue(responses.filter { it.status != 201 }.all { it.status == 409 && it.errorCode == "ALREADY_CLAIMED" })
        assertEquals(1, Db.claimCount(dealId))
        assertEquals(49, Db.stockAvailable(dealId))
    }
}
