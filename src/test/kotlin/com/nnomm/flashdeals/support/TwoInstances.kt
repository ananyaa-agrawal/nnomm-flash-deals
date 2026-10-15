package com.nnomm.flashdeals.support

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.flashdeals.AppConfig
import com.nnomm.flashdeals.db.Database
import com.nnomm.flashdeals.db.queryOne
import com.nnomm.flashdeals.db.update
import com.nnomm.flashdeals.payments.WebhookSignature
import com.nnomm.flashdeals.startServer
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

const val JWT_SECRET = "test-jwt-secret-not-used-anywhere-else-0123456789"
const val WEBHOOK_SECRET = "test-webhook-secret-not-used-anywhere-else-0123"

/**
 * Two real Netty servers, each with its own connection pool, sharing one
 * PostgreSQL — the same shape as `docker compose up`. Requests alternate
 * between them so every test exercises both instances.
 */
class TwoInstances(
    claimTtlSeconds: Long = 600,
    expiryIntervalMillis: Long = 200,
) : AutoCloseable {
    private val databases = List(2) {
        Database(TestPostgres.pool(TestPostgres.connection, size = 20, name = "instance-$it"))
    }
    private val servers = databases.mapIndexed { i, db ->
        val config = AppConfig(
            port = freePort(),
            databaseUrl = TestPostgres.connection.url,
            databaseUser = TestPostgres.connection.user,
            databasePassword = TestPostgres.connection.password,
            jwtSecret = JWT_SECRET,
            webhookSecret = WEBHOOK_SECRET,
            claimTtlSeconds = claimTtlSeconds,
            expiryIntervalMillis = expiryIntervalMillis,
            instanceId = "test-$i",
        )
        config.port to startServer(config, db)
    }
    val baseUrls = servers.map { "http://localhost:${it.first}" }

    val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val signer = WebhookSignature(WEBHOOK_SECRET)

    fun url(path: String, instance: Int = 0) = baseUrls[instance % 2] + path

    suspend fun send(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
        instance: Int = 0,
    ): Resp {
        val builder = HttpRequest.newBuilder(URI(url(path, instance)))
            .timeout(Duration.ofSeconds(60))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) builder.header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        return Resp(response.statusCode(), response.body(), response.headers().map())
    }

    suspend fun createDeal(
        admin: String,
        stock: Int = 50,
        price: Long = 9900,
        startsAt: Instant = Instant.now().minusSeconds(60),
        endsAt: Instant = Instant.now().plusSeconds(3600),
    ): String {
        val resp = send(
            "POST", "/v1/deals", token = admin,
            body = """{"title":"Chicken biryani box","price_paise":$price,"stock_total":$stock,"starts_at":"$startsAt","ends_at":"$endsAt"}""",
        )
        check(resp.status == 201) { "create deal failed: ${resp.status} ${resp.body}" }
        return resp.json["id"]!!.jsonPrimitive.content
    }

    suspend fun claim(customer: String, dealId: String, quantity: Int = 1, key: String = UUID.randomUUID().toString(), instance: Int = 0) =
        send("POST", "/v1/deals/$dealId/claims", """{"quantity":$quantity}""", customer, mapOf("Idempotency-Key" to key), instance)

    suspend fun webhook(body: String, signature: String? = sign(body), instance: Int = 0) =
        send("POST", "/v1/webhooks/payments", body, headers = signature?.let { mapOf("X-Payment-Signature" to it) } ?: emptyMap(), instance = instance)

    fun sign(body: String, at: Instant = Instant.now()) = signer.sign(at.epochSecond, body.toByteArray())

    override fun close() {
        servers.forEach { it.second.stop(100, 1000) }
        databases.forEach { it.close() }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}

data class Resp(val status: Int, val body: String, val headers: Map<String, List<String>>) {
    val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    val errorCode: String? get() = runCatching { json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content }.getOrNull()
    fun field(name: String): String = json[name]!!.jsonPrimitive.content
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    override fun toString() = "$status $body"
}

object Tokens {
    private val algorithm = Algorithm.HMAC256(JWT_SECRET)

    fun customer(id: UUID = UUID.randomUUID()): String = JWT.create()
        .withSubject(id.toString())
        .withClaim("role", "customer")
        .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
        .sign(algorithm)

    fun admin(restaurantId: UUID = UUID.randomUUID(), id: UUID = UUID.randomUUID()): String = JWT.create()
        .withSubject(id.toString())
        .withClaim("role", "restaurant_admin")
        .withClaim("restaurant_id", restaurantId.toString())
        .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
        .sign(algorithm)
}

/** Direct database reads for asserting invariants. */
object Db {
    fun stockAvailable(dealId: String): Int =
        TestPostgres.admin.transaction { it.queryOne("SELECT stock_available FROM deals WHERE id = ?::uuid", dealId) { rs -> rs.getInt(1) }!! }

    fun unitsHeld(dealId: String): Int = TestPostgres.admin.transaction {
        it.queryOne(
            "SELECT COALESCE(SUM(quantity), 0) FROM claims WHERE deal_id = ?::uuid AND status IN ('RESERVED','PAID','NEEDS_REVIEW')",
            dealId,
        ) { rs -> rs.getInt(1) }!!
    }

    fun claimCount(dealId: String): Int = TestPostgres.admin.transaction {
        it.queryOne("SELECT count(*) FROM claims WHERE deal_id = ?::uuid", dealId) { rs -> rs.getInt(1) }!!
    }

    fun status(claimId: String): String = TestPostgres.admin.transaction {
        it.queryOne("SELECT status FROM claims WHERE id = ?::uuid", claimId) { rs -> rs.getString(1) }!!
    }

    fun execute(sql: String, vararg params: Any?) = TestPostgres.admin.transaction { it.update(sql, *params) }
}
