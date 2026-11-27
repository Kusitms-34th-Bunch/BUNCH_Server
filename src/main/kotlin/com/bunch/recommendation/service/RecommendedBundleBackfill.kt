package com.bunch.recommendation.service

import com.bunch.recommendation.domain.RecommendedBundleQueueRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * The first calculation of the photos that were in the photo boxes before the new recommended bundles were switched on
 * (photo box 4.2.1: the recommended bundles are made of all the photos of the box). The recommended bundles are
 * calculated when a photo changes ([RecommendedBundleRecalcTrigger]), and the whole bunch is calculated then, the older
 * photos too. A bunch whose photos do not change would have none. This puts every day of every bunch in the queue once;
 * [RecommendedBundleScheduler] then calculates them as it calculates any due day, a few bunches at a time, so the load
 * is spread by the scheduler and not by this.
 *
 * It is run once at the start of the application when `reco.combination.backfill.enabled` is on, which is off by
 * default: it is turned on for the start that follows switching `reco.combination.enabled` on, and off again after. It
 * does nothing while `reco.combination.enabled` is off, because nothing is calculated then and the queue would only
 * fill. It is safe to run again: a day that is queued only has its time moved.
 *
 * The rules of what is kept are not changed here: the bundles whose last photo was added more than 30 days ago are not
 * saved (4.2.9 rule 4), and the photos a draft took are left out, as in every calculation.
 */
@Component
class RecommendedBundleBackfill(
    private val queue: RecommendedBundleQueueRepository,
    private val clock: Clock,
    @Value("\${reco.combination.enabled:false}")
    private val enabled: Boolean,
    @Value("\${reco.combination.backfill.enabled:false}")
    private val backfillEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Runs once the application is up, when the backfill is switched on. A failure is logged, the start goes on. */
    @EventListener(ApplicationReadyEvent::class)
    fun runAtStart() {
        if (!backfillEnabled) return

        try {
            run()
        } catch (ex: Exception) {
            // Never include the exception message: it may carry URLs or credentials.
            logger.warn("Recommended bundle backfill failed: {}", ex.javaClass.simpleName)
        }
    }

    /** Puts the days of all the bunches in the queue, due now, and returns how many days it wrote. */
    fun run(): Int {
        if (!enabled) return 0

        val days = queue.putAllDaysOfAllBunches(clock.instant())
        logger.info("Recommended bundle backfill queued days: {}", days)
        return days
    }
}
