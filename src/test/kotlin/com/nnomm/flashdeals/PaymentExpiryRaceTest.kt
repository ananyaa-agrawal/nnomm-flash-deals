package com.nnomm.flashdeals

import com.nnomm.flashdeals.support.Db
import com.nnomm.flashdeals.support.TestPostgres
import com.nnomm.flashdeals.support.Tokens
import com.nnomm.flashdeals.support.TwoInstances
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fires payment webhooks at both instances right around expires_at, while
 * both instances' expiry jobs sweep every 20 ms. Whatever the interleaving,
 * each claim must end in exactly one of the allowed states and the stock
 * books must balance.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentExpiryRaceTest {
    private lateinit var app: TwoInstances
    private val admin = Tokens.admin()

    @BeforeAll fun start() { app = TwoInstances(claimTtlSeconds = 1, expiryIntervalMillis = 20) }
    @AfterAll fun stop() = app.close()
    @BeforeEach fun clean() { TestPostgres.reset() }

    @Test
    fun `payments racing expiry leave every claim PAID or REFUND_PENDING and the stock invariant intact`() = runBlocking {
        repeat(3) { round ->
            val dealId = app.createDeal(admin, stock = 50, price = 1000)
            val claims = (0 until 50).map { i ->
                async { app.claim(Tokens.customer(), dealId, 1, instance = i) }
            }.awaitAll()
            assertTrue(claims.all { it.status == 201 })

            // Spread payments from 300 ms before to 300 ms after each claim's expiry.
            claims.mapIndexed { i, claim ->
                async {
                    val expiresAt = Instant.parse(claim.field("expires_at"))
                    val target = expiresAt.toEpochMilli() - 300 + (i * 12)
                    delay((target - System.currentTimeMillis()).coerceAtLeast(0))
                    val body = """{"event_id":"evt_${round}_$i","type":"payment.succeeded","claim_id":"${claim.field("id")}","amount_paise":1000,"occurred_at":"${Instant.now()}"}"""
                    app.webhook(body, instance = i)
                }
            }.awaitAll().forEach { assertEquals(200, it.status, it.body) }

            delay(300) // let the sweeps finish
            val statuses = claims.map { Db.status(it.field("id")) }
            val paid = statuses.count { it == "PAID" }
            assertTrue(statuses.all { it == "PAID" || it == "REFUND_PENDING" }, "round $round: $statuses")
            assertEquals(50 - paid, Db.stockAvailable(dealId), "round $round: refunded units are back, paid units are not")
            assertEquals(50, Db.stockAvailable(dealId) + Db.unitsHeld(dealId))
        }
    }

    @Test
    fun `an unpaid claim expires and its units come back`() = runBlocking {
        val dealId = app.createDeal(admin, stock = 5)
        val customer = Tokens.customer()
        val claim = app.claim(customer, dealId, 3)
        assertEquals(2, Db.stockAvailable(dealId))

        delay(1_500)

        assertEquals("EXPIRED", app.send("GET", "/v1/claims/${claim.field("id")}", token = customer).field("status"))
        assertEquals(5, app.send("GET", "/v1/deals/$dealId", token = customer).field("stock_available").toInt())
        // Once expired, the same customer may claim again.
        assertEquals(201, app.claim(customer, dealId, 3, instance = 1).status)
    }
}
