package com.nnomm.flashdeals.payments

import com.nnomm.flashdeals.Metrics
import com.nnomm.flashdeals.db.Database
import com.nnomm.flashdeals.db.queryOne
import com.nnomm.flashdeals.db.update
import com.nnomm.flashdeals.db.uuid
import com.nnomm.flashdeals.http.decodeBody
import com.nnomm.flashdeals.http.parseInstant
import com.nnomm.flashdeals.http.parseUuidOrNull
import com.nnomm.flashdeals.http.StrictLongSerializer
import com.nnomm.flashdeals.http.receiveLimitedBytes
import com.nnomm.flashdeals.http.unauthenticated
import com.nnomm.flashdeals.http.validation
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("com.nnomm.flashdeals.payments")

@Serializable
data class PaymentEventRequest(
    @SerialName("event_id") val eventId: String? = null,
    val type: String? = null,
    @SerialName("claim_id") val claimId: String? = null,
    @SerialName("amount_paise") @Serializable(with = StrictLongSerializer::class) val amountPaise: Long? = null,
    @SerialName("occurred_at") val occurredAt: String? = null,
)

data class PaymentEvent(
    val eventId: String,
    val type: String,
    val claimId: UUID,
    val amountPaise: Long,
    val occurredAt: Instant,
)

fun PaymentEventRequest.validate(): PaymentEvent {
    val eventId = eventId ?: validation("event_id is required")
    if (eventId.isBlank() || eventId.length > 255) validation("event_id must be 1-255 characters")
    return PaymentEvent(
        eventId = eventId,
        type = type ?: validation("type is required"),
        claimId = parseUuidOrNull(claimId) ?: validation("claim_id must be a UUID"),
        amountPaise = amountPaise ?: validation("amount_paise is required"),
        occurredAt = parseInstant("occurred_at", occurredAt),
    )
}

/** What happened to an event. Stored in payment_events.outcome for audit and support. */
enum class EventOutcome {
    DUPLICATE,               // same event_id seen before: nothing changes
    MARKED_PAID,
    MARKED_NEEDS_REVIEW,     // paid in time, but the wrong amount
    MARKED_REFUND_PENDING,   // paid after expires_at
    PAYMENT_FAILED,          // no change; customer may retry until expiry
    EXTRA_PAYMENT,           // a second successful charge on a settled claim: needs a manual refund
    UNKNOWN_CLAIM,
    UNKNOWN_TYPE,
}

class PaymentService(private val db: Database) {

    fun apply(event: PaymentEvent): EventOutcome = db.transaction { conn ->
        // 1. Deduplicate. The primary key on event_id makes a duplicate a no-op
        //    even when two deliveries race: the second INSERT waits for the
        //    first transaction, then sees the conflict.
        val fresh = conn.update(
            """
            INSERT INTO payment_events (event_id, claim_id, type, amount_paise, occurred_at, outcome)
            VALUES (?, ?, ?, ?, ?, 'PROCESSING')
            ON CONFLICT (event_id) DO NOTHING
            """,
            event.eventId, event.claimId, event.type, event.amountPaise, event.occurredAt,
        )
        if (fresh == 0) return@transaction EventOutcome.DUPLICATE

        val outcome = decideAndApply(conn, event)
        conn.update("UPDATE payment_events SET outcome = ? WHERE event_id = ?", outcome.name, event.eventId)
        outcome
    }

    private fun decideAndApply(conn: java.sql.Connection, event: PaymentEvent): EventOutcome {
        if (event.type != SUCCEEDED && event.type != FAILED) return EventOutcome.UNKNOWN_TYPE

        // deal_id never changes, so reading it without a lock is safe.
        val dealId = conn.queryOne("SELECT deal_id FROM claims WHERE id = ?", event.claimId) { it.uuid("deal_id") }
            ?: return EventOutcome.UNKNOWN_CLAIM

        if (event.type == FAILED) return EventOutcome.PAYMENT_FAILED

        // 2. Same lock order as claims and the expiry job: deal first, then
        //    claim. A webhook and the expiry job therefore run one after the
        //    other on this claim, never interleaved (DESIGN.md, question 3).
        conn.queryOne("SELECT 1 FROM deals WHERE id = ? FOR UPDATE", dealId) { }
        val claim = conn.queryOne(
            """
            SELECT status, quantity, amount_paise, clock_timestamp() < expires_at AS in_time
            FROM claims WHERE id = ? FOR UPDATE
            """,
            event.claimId,
        ) { rs -> LockedClaim(rs.getString("status"), rs.getInt("quantity"), rs.getLong("amount_paise"), rs.getBoolean("in_time")) }!!

        return when (claim.status) {
            "RESERVED" -> when {
                // "Processed after expires_at" is judged on the DB clock at this
                // moment, not on the provider's occurred_at.
                !claim.inTime -> {
                    setStatus(conn, event.claimId, "REFUND_PENDING")
                    // Still RESERVED, so its units were never returned: return them now.
                    conn.update("UPDATE deals SET stock_available = stock_available + ? WHERE id = ?", claim.quantity, dealId)
                    EventOutcome.MARKED_REFUND_PENDING
                }
                event.amountPaise == claim.amountPaise -> {
                    setStatus(conn, event.claimId, "PAID")
                    EventOutcome.MARKED_PAID
                }
                else -> {
                    setStatus(conn, event.claimId, "NEEDS_REVIEW") // units stay held
                    EventOutcome.MARKED_NEEDS_REVIEW
                }
            }
            "EXPIRED" -> {
                // Units already went back to stock when it expired; don't take them again.
                setStatus(conn, event.claimId, "REFUND_PENDING")
                EventOutcome.MARKED_REFUND_PENDING
            }
            // PAID, NEEDS_REVIEW, REFUND_PENDING: the claim is already settled.
            // A second successful charge is money we must give back, but it
            // must not change the claim or its units. Record it for refund.
            else -> EventOutcome.EXTRA_PAYMENT
        }
    }

    private fun setStatus(conn: java.sql.Connection, claimId: UUID, status: String) {
        conn.update("UPDATE claims SET status = ?, updated_at = clock_timestamp() WHERE id = ?", status, claimId)
    }

    private class LockedClaim(val status: String, val quantity: Int, val amountPaise: Long, val inTime: Boolean)

    companion object {
        const val SUCCEEDED = "payment.succeeded"
        const val FAILED = "payment.failed"
    }
}

@Serializable
data class WebhookAck(val received: Boolean = true, val outcome: String)

fun Route.webhookRoutes(service: PaymentService, signature: WebhookSignature, metrics: Metrics) {
    // No JWT here: the HMAC signature is the authentication.
    post("/v1/webhooks/payments") {
        val raw = call.receiveLimitedBytes()
        // Verify the exact bytes we received, BEFORE parsing them.
        if (!signature.isValid(call.request.headers["X-Payment-Signature"], raw, Instant.now())) {
            metrics.webhookRejected()
            unauthenticated("Missing, malformed, stale or invalid webhook signature")
        }
        val event = decodeBody(PaymentEventRequest.serializer(), raw).validate()
        val outcome = withContext(Dispatchers.IO) { service.apply(event) }
        metrics.webhookOutcome(outcome.name)
        log.info("payment event={} type={} claim={} outcome={}", event.eventId, event.type, event.claimId, outcome)
        if (outcome == EventOutcome.UNKNOWN_CLAIM || outcome == EventOutcome.EXTRA_PAYMENT) {
            log.warn("payment event={} needs attention: {}", event.eventId, outcome)
        }
        call.respond(HttpStatusCode.OK, WebhookAck(outcome = outcome.name))
    }
}
