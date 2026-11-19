package com.bunch.recommendation.service

import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.domain.combination.BundleExpiry
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Deletes the recommended bundles that are no longer shown, 30 days after the day their last photo was added (photo box
 * specification 4.2.9 rule 4, [BundleExpiry]). They are deleted and not recorded. The calculation of a bunch does not
 * make them again, so a bundle of old photos stays gone until a photo that was added since belongs to it.
 *
 * It runs once a day, so a bundle may be there up to a day longer than 30 days, as the specification allows (rule 3).
 */
@Service
class RecommendedBundleExpiration(
    private val bundles: RecommendedBundleRepository,
    private val expiry: BundleExpiry
) {
    /** Deletes the bundles that are expired at [now] and returns how many. */
    fun expire(now: Instant): Int = bundles.deleteExpired(expiry.cutoff(now))
}
