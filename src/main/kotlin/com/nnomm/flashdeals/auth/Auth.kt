package com.nnomm.flashdeals.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Payload
import com.nnomm.flashdeals.http.ErrorCode
import com.nnomm.flashdeals.http.errorBody
import com.nnomm.flashdeals.http.forbidden
import com.nnomm.flashdeals.http.parseUuidOrNull
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.util.UUID

const val JWT_AUTH = "jwt"

enum class Role(val claim: String) {
    CUSTOMER("customer"),
    RESTAURANT_ADMIN("restaurant_admin");

    companion object {
        fun fromClaim(value: String?) = entries.firstOrNull { it.claim == value }
    }
}

data class AuthUser(val userId: UUID, val role: Role, val restaurantId: UUID?)

/**
 * HS256 only. JWT.require(Algorithm.HMAC256(...)) rejects tokens whose header
 * says anything else — including "none" and HS512 — before the signature is
 * even checked. exp is mandatory, not just validated when present.
 */
fun AuthenticationConfig.configureJwt(secret: String) {
    jwt(JWT_AUTH) {
        verifier(JWT.require(Algorithm.HMAC256(secret)).build())
        validate { credential -> toAuthUser(credential.payload) }
        challenge { _, _ ->
            call.respond(
                ErrorCode.UNAUTHENTICATED.status,
                errorBody(ErrorCode.UNAUTHENTICATED, "Missing, invalid or expired bearer token"),
            )
        }
    }
}

fun toAuthUser(payload: Payload): AuthUser? {
    if (payload.expiresAt == null) return null
    val userId = parseUuidOrNull(payload.subject) ?: return null
    val role = Role.fromClaim(payload.getClaim("role").asString()) ?: return null
    val restaurantId = parseUuidOrNull(payload.getClaim("restaurant_id").asString())
    if (role == Role.RESTAURANT_ADMIN && restaurantId == null) return null
    return AuthUser(userId, role, if (role == Role.RESTAURANT_ADMIN) restaurantId else null)
}

fun ApplicationCall.user(): AuthUser = principal<AuthUser>() ?: error("route is not authenticated")

fun ApplicationCall.requireRole(role: Role): AuthUser {
    val user = user()
    if (user.role != role) forbidden("Only ${role.claim} users can use this endpoint")
    return user
}
