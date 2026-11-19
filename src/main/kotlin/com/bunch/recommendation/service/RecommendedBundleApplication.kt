package com.bunch.recommendation.service

import com.bunch.recommendation.domain.RecommendationRepository
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.domain.UsedCandidate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Takes the recommended bundle of the new combination a draft is made from (photo box 4.1.3 rules 5 and 8, 4.2.9 rule
 * 5), like [RecommendationService.useCandidate] does for the old ones. The bundle and its photo rows are deleted at
 * once, so it is not on the sheet while the next calculation waits for its five minutes. All its photos are noted as
 * used, so the photos the draft does not take are only recommended again when a photo that is not among them is added
 * (the calculation reads the note, [RecommendedBundleRecalculation]). The draft gets the title and the first day.
 *
 * It gives nothing when the bundle is not there any more, is not shown yet, or belongs to another bunch: the draft is
 * made without it, from the photos the request names. The caller is the creation of the draft, which has locked the
 * bunch, and the calculation locks it too, so a bundle is never taken while it is saved.
 */
@Service
class RecommendedBundleApplication(
    private val bundles: RecommendedBundleRepository,
    private val recommendations: RecommendationRepository,
    private val clock: Clock
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun use(bunchId: Long, bundleId: Long): UsedCandidate? {
        val bundle = bundles.findByIdAndBunchId(bundleId, bunchId)?.takeIf { !it.visibleFrom.isAfter(clock.instant()) }
            ?: return null

        val startDate = recommendations.findRecommendedDateRanges(listOf(bundleId)).firstOrNull()?.startDate
            ?: bundle.dayKey
        recommendations.markRecommendedBundlePhotosUsed(bunchId, bundleId)
        bundles.deleteAllByIdInBatch(listOf(bundleId))
        return UsedCandidate(bundle.title, startDate)
    }
}
