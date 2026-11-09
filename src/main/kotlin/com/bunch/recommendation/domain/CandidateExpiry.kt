package com.bunch.recommendation.domain

import java.time.Instant

/**
 * When a recommended bundle is no longer shown (4.2.9 rule 4): 30 days after the day the last photo was added. The
 * days are counted as days of the settings' time zone, so a bundle whose last photo was added on October 1 is shown
 * through October 31 and is gone from November 1 (the example of 4.2.9 rule 5: November 5, shown through December 5).
 * It reads no clock and no database.
 */
class CandidateExpiry(private val settings: RecommendationSettings = RecommendationSettings()) {
    /** A bundle whose last photo was added before this instant is expired at [now]. */
    fun cutoff(now: Instant): Instant {
        val today = now.atZone(settings.zone).toLocalDate()
        return today.minusDays(settings.expireDays.toLong()).atStartOfDay(settings.zone).toInstant()
    }

    fun isExpired(lastPhotoAddedAt: Instant, now: Instant): Boolean = lastPhotoAddedAt.isBefore(cutoff(now))
}
