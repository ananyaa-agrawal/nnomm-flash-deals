package com.nnomm.flashdeals.claims

import com.nnomm.flashdeals.db.Database
import com.nnomm.flashdeals.db.instant
import com.nnomm.flashdeals.db.queryList
import com.nnomm.flashdeals.db.queryOne
import com.nnomm.flashdeals.db.update
import com.nnomm.flashdeals.db.uuid
import com.nnomm.flashdeals.http.ApiException
import com.nnomm.flashdeals.http.ErrorCode
import com.nnomm.flashdeals.http.errorBody
import com.nnomm.flashdeals.http.iso
import com.nnomm.flashdeals.http.StrictLongSerializer
import com.nnomm.flashdeals.http.json
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

@Serializable
data class ClaimRequest(@Serializable(with = StrictLongSerializer::class) val quantity: Long? = null)

@Serializable
data class ClaimResponse(
    val id: String,
    @SerialName("deal_id") val dealId: String,
    @SerialName("customer_id") val customerId: String,
    val quantity: Int,
    @SerialName("amount_paise") val amountPaise: Long,
    val status: String,
    @SerialName("expires_at") val expiresAt: String,
)

/** What the claim endpoint answers: either freshly computed or replayed from the idempotency table. */
data class ClaimResult(val status: HttpStatusCode, val body: String, val replayed: Boolean)

/** Statuses whose claims hold units out of stock_available. */
val ACTIVE_STATUSES = listOf("RESERVED", "PAID", "NEEDS_REVIEW")

class ClaimService(private val db: Database, private val claimTtlSeconds: Long) {

    /**
     * The whole claim — idempotency record, stock decrement, claim row and the
     * stored response — commits in ONE transaction. So either everything
     * about this request is durable, or nothing is (DESIGN.md, question 2).
     */
    fun claim(customerId: UUID, dealId: UUID, quantity: Int, key: String): ClaimResult = db.transaction { conn ->
        // 1. Reserve the idempotency key. A concurrent request with the same
        //    key blocks here on the primary key until we commit or roll back.
        //    null means we now own the key; anything else is a replay to return.
        reserveIdempotencyKey(conn, customerId, key, dealId, quantity)?.let { return@transaction it }

        // 2. Lock the deal row. Every claim, expiry and webhook on this deal
        //    queues here, across both instances (DESIGN.md, question 1).
        val deal = conn.queryOne(
            """
            SELECT price_paise, stock_available, starts_at, ends_at, clock_timestamp() AS now
            FROM deals WHERE id = ? FOR UPDATE
            """,
            dealId,
        ) { rs -> LockedDeal(rs.getLong("price_paise"), rs.getInt("stock_available"), rs.instant("starts_at"), rs.instant("ends_at"), rs.instant("now")) }
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Deal not found") // rolls back the key: 404s are not stored

        // 3. Put units from lapsed reservations back before deciding anything,
        //    so a customer whose claim just expired can claim again at once.
        val released = releaseExpiredReservations(conn, dealId)
        val available = deal.stockAvailable + released

        val outcome: ClaimResult = when {
            deal.now.isBefore(deal.startsAt) || !deal.now.isBefore(deal.endsAt) ->
                conflict(ErrorCode.DEAL_NOT_LIVE, "This deal is not live")

            hasActiveClaim(conn, dealId, customerId) ->
                conflict(ErrorCode.ALREADY_CLAIMED, "You already hold an active claim on this deal")

            available < quantity ->
                conflict(ErrorCode.SOLD_OUT, "Not enough units left for this quantity")

            else -> {
                conn.update("UPDATE deals SET stock_available = stock_available - ? WHERE id = ?", quantity, dealId)
                val claim = conn.queryOne(
                    """
                    INSERT INTO claims (id, deal_id, customer_id, quantity, amount_paise, status, expires_at)
                    VALUES (?, ?, ?, ?, ?, 'RESERVED', date_trunc('milliseconds', clock_timestamp()) + make_interval(secs => ?))
                    RETURNING id, deal_id, customer_id, quantity, amount_paise, status, expires_at
                    """,
                    UUID.randomUUID(), dealId, customerId, quantity, deal.pricePaise * quantity, claimTtlSeconds.toDouble(),
                    map = ::toClaim,
                )!!
                ClaimResult(HttpStatusCode.Created, json.encodeToString(ClaimResponse.serializer(), claim), replayed = false)
            }
        }

        // 4. Store the outcome (201 or 409) against the key, in the same transaction.
        conn.update(
            "UPDATE idempotency_keys SET response_status = ?, response_body = ? WHERE customer_id = ? AND idempotency_key = ?",
            outcome.status.value, outcome.body, customerId, key,
        )
        outcome
    }

    /**
     * Returns null when this request now owns [key], or the result to send back
     * (a replay, or 422) when a committed request already used it.
     */
    private fun reserveIdempotencyKey(conn: Connection, customerId: UUID, key: String, dealId: UUID, quantity: Int): ClaimResult? {
        // Keys are honoured for 24 hours; after that the key is free to reuse.
        conn.update(
            "DELETE FROM idempotency_keys WHERE customer_id = ? AND idempotency_key = ? AND created_at < now() - interval '24 hours'",
            customerId, key,
        )
        // Bound how long we wait behind an in-flight request with the same key.
        conn.update("SET LOCAL lock_timeout = '10s'")
        val inserted = try {
            conn.update(
                """
                INSERT INTO idempotency_keys (customer_id, idempotency_key, deal_id, quantity, response_status, response_body)
                VALUES (?, ?, ?, ?, 0, '')
                ON CONFLICT (customer_id, idempotency_key) DO NOTHING
                """,
                customerId, key, dealId, quantity,
            )
        } catch (e: PSQLException) {
            if (e.sqlState == LOCK_NOT_AVAILABLE) {
                throw ApiException(ErrorCode.REQUEST_IN_PROGRESS, "A request with this Idempotency-Key is still in progress")
            }
            throw e
        }
        conn.update("SET LOCAL lock_timeout = 0")
        if (inserted == 1) return null

        // ON CONFLICT only fires against a COMMITTED row, and rows are only
        // committed together with their final response — so this is complete.
        val stored = conn.queryOne(
            "SELECT deal_id, quantity, response_status, response_body FROM idempotency_keys WHERE customer_id = ? AND idempotency_key = ?",
            customerId, key,
        ) { rs -> StoredRequest(rs.uuid("deal_id"), rs.getInt("quantity"), rs.getInt("response_status"), rs.getString("response_body")) }
            ?: throw ApiException(ErrorCode.REQUEST_IN_PROGRESS, "A request with this Idempotency-Key is still in progress")

        if (stored.dealId != dealId || stored.quantity != quantity) {
            throw ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was already used for a different request")
        }
        return ClaimResult(HttpStatusCode.fromValue(stored.status), stored.body, replayed = true)
    }

    private fun hasActiveClaim(conn: Connection, dealId: UUID, customerId: UUID): Boolean =
        conn.queryOne(
            "SELECT 1 FROM claims WHERE deal_id = ? AND customer_id = ? AND status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW')",
            dealId, customerId,
        ) { true } ?: false

    private fun conflict(code: ErrorCode, message: String) =
        ClaimResult(code.status, json.encodeToString(com.nnomm.flashdeals.http.ErrorBody.serializer(), errorBody(code, message)), replayed = false)

    /**
     * A RESERVED claim past expires_at is shown as EXPIRED straight away, even if
     * the background job has not flipped the row yet (invariant 4).
     */
    fun find(claimId: UUID): ClaimWithOwner? = db.transaction { conn ->
        conn.queryOne(
            """
            SELECT c.id, c.deal_id, c.customer_id, c.quantity, c.amount_paise, c.expires_at,
                   CASE WHEN c.status = 'RESERVED' AND c.expires_at <= clock_timestamp() THEN 'EXPIRED'
                        ELSE c.status END AS status,
                   d.restaurant_id
            FROM claims c JOIN deals d ON d.id = c.deal_id
            WHERE c.id = ?
            """,
            claimId,
        ) { rs -> ClaimWithOwner(toClaim(rs), rs.uuid("restaurant_id")) }
    }

    private data class LockedDeal(
        val pricePaise: Long,
        val stockAvailable: Int,
        val startsAt: java.time.Instant,
        val endsAt: java.time.Instant,
        val now: java.time.Instant,
    )

    private data class StoredRequest(val dealId: UUID, val quantity: Int, val status: Int, val body: String)

    companion object {
        private const val LOCK_NOT_AVAILABLE = "55P03"
    }
}

data class ClaimWithOwner(val claim: ClaimResponse, val restaurantId: UUID)

fun toClaim(rs: ResultSet) = ClaimResponse(
    id = rs.uuid("id").toString(),
    dealId = rs.uuid("deal_id").toString(),
    customerId = rs.uuid("customer_id").toString(),
    quantity = rs.getInt("quantity"),
    amountPaise = rs.getLong("amount_paise"),
    status = rs.getString("status"),
    expiresAt = rs.instant("expires_at").iso(),
)

/**
 * Flips this deal's lapsed RESERVED claims to EXPIRED and returns their units
 * to stock. The caller MUST already hold the deal row lock, which is what
 * makes this safe against a concurrent payment webhook or claim.
 *
 * @return the number of units put back.
 */
fun releaseExpiredReservations(conn: Connection, dealId: UUID): Int {
    val quantities = conn.queryList(
        """
        UPDATE claims SET status = 'EXPIRED', updated_at = clock_timestamp()
        WHERE deal_id = ? AND status = 'RESERVED' AND expires_at <= clock_timestamp()
        RETURNING quantity
        """,
        dealId,
    ) { it.getInt("quantity") }
    val units = quantities.sum()
    if (units > 0) {
        conn.update("UPDATE deals SET stock_available = stock_available + ? WHERE id = ?", units, dealId)
    }
    return units
}
