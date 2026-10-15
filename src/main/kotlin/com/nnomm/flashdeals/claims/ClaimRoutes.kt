package com.nnomm.flashdeals.claims

import com.nnomm.flashdeals.Metrics
import com.nnomm.flashdeals.auth.JWT_AUTH
import com.nnomm.flashdeals.auth.Role
import com.nnomm.flashdeals.auth.requireRole
import com.nnomm.flashdeals.auth.user
import com.nnomm.flashdeals.db.Database
import com.nnomm.flashdeals.deals.DealRepository
import com.nnomm.flashdeals.http.ApiException
import com.nnomm.flashdeals.http.ErrorCode
import com.nnomm.flashdeals.http.decodeBody
import com.nnomm.flashdeals.http.notFound
import com.nnomm.flashdeals.http.parseUuidOrNull
import com.nnomm.flashdeals.http.receiveLimitedBytes
import com.nnomm.flashdeals.http.respondJson
import com.nnomm.flashdeals.http.validation
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.nnomm.flashdeals.claims")

private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9_-]{1,64}$")

fun Route.claimRoutes(db: Database, service: ClaimService, metrics: Metrics) {
    authenticate(JWT_AUTH) {
        post("/v1/deals/{dealId}/claims") {
            // Checks that reject before anything is stored: 401 (authenticate), 403, 400, 413, 404.
            val customer = call.requireRole(Role.CUSTOMER)
            val key = call.request.headers["Idempotency-Key"]
            if (key == null || !IDEMPOTENCY_KEY.matches(key)) {
                throw ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "Idempotency-Key header is required: 1-64 characters from A-Z, a-z, 0-9, - and _",
                )
            }
            val request = decodeBody(ClaimRequest.serializer(), call.receiveLimitedBytes())
            val quantity = request.quantity ?: validation("quantity is required")
            if (quantity !in 1..3) validation("quantity must be between 1 and 3")

            val dealId = parseUuidOrNull(call.parameters["dealId"]) ?: notFound("Deal not found")
            db.tx { DealRepository.find(it, dealId) } ?: notFound("Deal not found")

            val result = withContext(Dispatchers.IO) {
                service.claim(customer.userId, dealId, quantity.toInt(), key)
            }
            metrics.claimOutcome(result.status.value, result.replayed)
            log.info(
                "claim deal={} customer={} quantity={} status={} replayed={}",
                dealId, customer.userId, quantity, result.status.value, result.replayed,
            )
            if (result.replayed) call.response.header("Idempotent-Replayed", "true")
            call.respondJson(result.status, result.body)
        }

        get("/v1/claims/{claimId}") {
            val user = call.user()
            val id = parseUuidOrNull(call.parameters["claimId"]) ?: notFound("Claim not found")
            val found = withContext(Dispatchers.IO) { service.find(id) } ?: notFound("Claim not found")
            // 404 rather than 403, so callers cannot probe which claim ids exist.
            val visible = when (user.role) {
                Role.CUSTOMER -> found.claim.customerId == user.userId.toString()
                Role.RESTAURANT_ADMIN -> found.restaurantId == user.restaurantId
            }
            if (!visible) notFound("Claim not found")
            call.respond(found.claim)
        }
    }
}
