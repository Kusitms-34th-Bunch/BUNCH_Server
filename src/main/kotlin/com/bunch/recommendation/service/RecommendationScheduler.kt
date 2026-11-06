package com.bunch.recommendation.service

import com.bunch.recommendation.domain.RecommendationSettings
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Calculates the recommended bundles of the bunches whose photos stopped changing for the quiet time (4.2.9 rule 2:
 * five minutes after the last change, once). It lives outside the service so each bunch is calculated in a
 * transaction of its own through the service.
 */
@Component
class RecommendationScheduler(
    private val recommendations: RecommendationService,
    private val settings: RecommendationSettings,
    @Value("\${recommendation.recalculation.batch-size:20}")
    private val batchSize: Int
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Scheduling is switched on by SchedulingConfig; without it this method is never called.
    @Scheduled(fixedDelayString = "\${recommendation.recalculation.poll-interval-ms:30000}")
    fun poll() {
        try {
            runOnce()
        } catch (ex: Exception) {
            // Never include the exception message: it may carry URLs or credentials.
            logger.warn("Recommendation recalculation polling failed: {}", ex.javaClass.simpleName)
        }
    }

    // Once a day (4.2.9 rule 3: checked once a day, so a bundle may stay up to a day longer). The time and the zone can
    // be set; a run that is missed is caught up the next day.
    @Scheduled(
        cron = "\${recommendation.expiry.cron:0 30 4 * * *}",
        zone = "\${recommendation.expiry.zone:Asia/Seoul}"
    )
    fun expire() {
        try {
            runExpiry()
        } catch (ex: Exception) {
            // Never include the exception message: it may carry URLs or credentials.
            logger.warn("Recommendation expiry failed: {}", ex.javaClass.simpleName)
        }
    }

    /** Deletes the expired recommended bundles and returns how many. [now] is a parameter so tests can move time. */
    fun runExpiry(now: Instant = Instant.now()): Int {
        val removed = recommendations.expireCandidates(now)
        if (removed > 0) logger.info("Expired recommended bundles deleted: {}", removed)
        return removed
    }

    /**
     * Calculates the quiet bunches and returns how many it looked at. [now] is a parameter so tests can move time
     * without sleeping. A bunch that fails keeps its noted dates and is tried again at the next call.
     */
    fun runOnce(now: Instant = Instant.now()): Int {
        val cutoff = now.minus(settings.quietTime)
        val bunchIds = recommendations.quietBunchIds(cutoff, batchSize)

        bunchIds.forEach { bunchId ->
            try {
                recommendations.recalculateIfQuiet(bunchId, cutoff)
            } catch (ex: Exception) {
                logger.warn(
                    "Recommendation recalculation failed: bunchId={}, error={}",
                    bunchId,
                    ex.javaClass.simpleName
                )
            }
        }
        return bunchIds.size
    }
}
