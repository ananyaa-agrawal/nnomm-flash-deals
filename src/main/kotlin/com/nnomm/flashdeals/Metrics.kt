package com.nnomm.flashdeals

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

class Metrics(val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)) {

    fun claimOutcome(status: Int, replayed: Boolean) =
        registry.counter("flashdeals_claims_total", "status", status.toString(), "replayed", replayed.toString()).increment()

    fun webhookOutcome(outcome: String) =
        registry.counter("flashdeals_payment_events_total", "outcome", outcome).increment()

    fun webhookRejected() =
        registry.counter("flashdeals_payment_events_rejected_total").increment()

    fun unitsReleased(units: Int) =
        registry.counter("flashdeals_expired_units_released_total").increment(units.toDouble())
}
