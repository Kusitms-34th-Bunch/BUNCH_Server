package com.bunch.recommendation.domain.combination

import java.time.Instant

/**
 * When a recommended bundle is no longer shown (photo box specification 4.2.9 rule 4): [CombinationSettings.expireDays]
 * (30) days after the day the last photo of the bundle was added. The days are counted as days of the zone of the
 * settings, so a bundle whose last photo was added on October 1 is shown through October 31 and is gone from
 * November 1.
 * A bundle that has expired is deleted and is not made again until a photo that was added since belongs to it (a
 * bundle of the same old photos would be made again by the next calculation, so the calculation leaves it out, 4.2.9
 * rule 5).
 * It reads no clock and no database.
 */
class BundleExpiry(
    private val settings: CombinationSettings = CombinationSettings()
) {
    /** A bundle whose last photo was added before this instant is expired at [now]. */
    fun cutoff(now: Instant): Instant {
        val today = now.atZone(settings.defaultZone).toLocalDate()
        return today.minusDays(settings.expireDays.toLong()).atStartOfDay(settings.defaultZone).toInstant()
    }

    fun isExpired(lastPhotoAddedAt: Instant, now: Instant): Boolean = lastPhotoAddedAt.isBefore(cutoff(now))
}
