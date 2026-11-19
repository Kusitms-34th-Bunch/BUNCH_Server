package com.bunch.recommendation.service

import com.bunch.recommendation.domain.RecommendedBundleQueueRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Calculates the bunches that have a day due in the queue (recommended bundle specification 10-2). It lives outside the
 * service so each bunch is calculated in a transaction of its own through the service, and a bunch that fails does not
 * stop the others.
 *
 * It does nothing while `reco.combination.enabled` is off, which is the default (see [RecommendedBundleRecalcTrigger]).
 */
@Component
class RecommendedBundleScheduler(
    private val queue: RecommendedBundleQueueRepository,
    private val recalculation: RecommendedBundleRecalculation,
    private val expiration: RecommendedBundleExpiration,
    @Value("\${reco.combination.enabled:false}")
    private val enabled: Boolean,
    @Value("\${reco.combination.batch-size:20}")
    private val batchSize: Int
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Scheduling is switched on by SchedulingConfig; without it this method is never called.
    @Scheduled(fixedDelayString = "\${reco.combination.poll-interval-ms:30000}")
    fun poll() {
        try {
            runOnce()
        } catch (ex: Exception) {
            // Never include the exception message: it may carry URLs or credentials.
            logger.warn("Recommended bundle polling failed: {}", ex.javaClass.simpleName)
        }
    }

    // Once a day (photo box 4.2.9 rule 3: checked once a day, so a bundle may stay up to a day longer). The time and
    // the zone can be set; a run that is missed is caught up the next day, because all that is expired is deleted.
    @Scheduled(
        cron = "\${reco.combination.expiry.cron:0 30 4 * * *}",
        zone = "\${reco.combination.expiry.zone:Asia/Seoul}"
    )
    fun expire() {
        try {
            runExpiry()
        } catch (ex: Exception) {
            // Never include the exception message: it may carry URLs or credentials.
            logger.warn("Recommended bundle expiry failed: {}", ex.javaClass.simpleName)
        }
    }

    /** Deletes the expired recommended bundles and returns how many. [now] is a parameter so tests can move time. */
    fun runExpiry(now: Instant = Instant.now()): Int {
        if (!enabled) return 0

        val removed = expiration.expire(now)
        if (removed > 0) logger.info("Expired recommended bundles deleted: {}", removed)
        return removed
    }

    /**
     * Calculates the bunches that are due and returns how many it looked at. [now] is a parameter so tests can move
     * time without sleeping. A bunch that fails keeps its days in the queue and is tried again at the next call.
     */
    fun runOnce(now: Instant = Instant.now()): Int {
        if (!enabled) return 0

        val bunchIds = queue.findDueBunchIds(now, batchSize)
        bunchIds.forEach { bunchId ->
            try {
                recalculation.recalculate(bunchId, now)
            } catch (ex: Exception) {
                logger.warn(
                    "Recommended bundle calculation failed: bunchId={}, error={}",
                    bunchId,
                    ex.javaClass.simpleName
                )
            }
        }
        return bunchIds.size
    }
}
