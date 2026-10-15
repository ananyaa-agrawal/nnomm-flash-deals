package com.nnomm.flashdeals

import com.nnomm.flashdeals.auth.configureJwt
import com.nnomm.flashdeals.claims.ClaimService
import com.nnomm.flashdeals.claims.ExpiryJob
import com.nnomm.flashdeals.claims.claimRoutes
import com.nnomm.flashdeals.db.Database
import com.nnomm.flashdeals.deals.dealRoutes
import com.nnomm.flashdeals.http.ApiException
import com.nnomm.flashdeals.http.ErrorCode
import com.nnomm.flashdeals.http.errorBody
import com.nnomm.flashdeals.http.json
import com.nnomm.flashdeals.payments.PaymentService
import com.nnomm.flashdeals.payments.WebhookSignature
import com.nnomm.flashdeals.payments.webhookRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

private val log = LoggerFactory.getLogger("com.nnomm.flashdeals.Application")

fun main() {
    val config = AppConfig.fromEnv()
    val db = Database.connect(config)
    Database.migrate(db.dataSource)
    startServer(config, db, wait = true)
}

fun startServer(config: AppConfig, db: Database, wait: Boolean = false): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config, db)
    }.start(wait = wait)

fun Application.module(config: AppConfig, db: Database) {
    val metrics = Metrics()
    val claims = ClaimService(db, config.claimTtlSeconds)
    val payments = PaymentService(db)
    val signature = WebhookSignature(config.webhookSecret)

    install(CallId) {
        retrieveFromHeader("X-Request-Id")
        generate { UUID.randomUUID().toString() }
        verify { it.length in 1..128 }
        replyToHeader("X-Request-Id")
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("request_id")
        filter { it.request.path() != "/metrics" }
        // Default format is method + path + status: no headers, so no tokens
        // or signatures ever reach the logs.
    }
    install(MicrometerMetrics) { registry = metrics.registry }
    install(ContentNegotiation) { json(json) }
    install(Authentication) { configureJwt(config.jwtSecret) }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.code.status, errorBody(e.code, e.message))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, errorBody(ErrorCode.VALIDATION_FAILED, "Malformed request"))
        }
        exception<Throwable> { call, e ->
            log.error("unhandled error on {} {}", call.request.local.method.value, call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, errorBody(ErrorCode.INTERNAL, "Internal error"))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, errorBody(ErrorCode.NOT_FOUND, "Not found"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respond(HttpStatusCode.NotFound, errorBody(ErrorCode.NOT_FOUND, "Not found"))
        }
    }

    routing {
        get("/health") { call.respondText("ok") }
        get("/metrics") { call.respondText(metrics.registry.scrape()) }
        dealRoutes(db)
        claimRoutes(db, claims, metrics)
        webhookRoutes(payments, signature, metrics)
    }

    val expiry = ExpiryJob(db, config.expiryIntervalMillis, metrics).start(this)
    monitor.subscribe(ApplicationStopping) { expiry.cancel() }
    log.info("instance {} started, claim TTL {}s", config.instanceId, config.claimTtlSeconds)
}
