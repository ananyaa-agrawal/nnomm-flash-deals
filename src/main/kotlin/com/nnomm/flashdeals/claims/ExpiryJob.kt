package com.nnomm.flashdeals.claims

import com.nnomm.flashdeals.Metrics
import com.nnomm.flashdeals.db.Database
import com.nnomm.flashdeals.db.queryList
import com.nnomm.flashdeals.db.queryOne
import com.nnomm.flashdeals.db.uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Returns units from lapsed reservations to stock. Runs on every instance;
 * running twice is harmless because each deal is processed under its row lock
 * and a lapsed claim can only be flipped from RESERVED once.
 */
class ExpiryJob(private val db: Database, private val intervalMillis: Long, private val metrics: Metrics) {
    private val log = LoggerFactory.getLogger(ExpiryJob::class.java)

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                runOnce()
            } catch (e: Exception) {
                log.error("expiry sweep failed", e)
            }
            delay(intervalMillis)
        }
    }

    /** @return units returned to stock in this sweep. */
    fun runOnce(): Int {
        val dueDeals = db.transaction { conn ->
            conn.queryList(
                "SELECT DISTINCT deal_id FROM claims WHERE status = 'RESERVED' AND expires_at <= clock_timestamp() LIMIT 500",
            ) { it.uuid("deal_id") }
        }
        var released = 0
        for (dealId in dueDeals) {
            released += db.transaction { conn ->
                // SKIP LOCKED: if a claim or webhook holds this deal right now,
                // move on; the claim path releases lapsed units itself, and the
                // next sweep (1s later) picks up anything left.
                conn.queryOne("SELECT 1 FROM deals WHERE id = ? FOR UPDATE SKIP LOCKED", dealId) { true }
                    ?.let { releaseExpiredReservations(conn, dealId) } ?: 0
            }
        }
        if (released > 0) {
            metrics.unitsReleased(released)
            log.info("expiry sweep returned {} units across {} deals", released, dueDeals.size)
        }
        return released
    }
}
