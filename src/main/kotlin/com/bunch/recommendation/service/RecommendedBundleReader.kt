package com.bunch.recommendation.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.PhotoImageUrls
import com.bunch.recommendation.domain.RecommendationRepository
import com.bunch.recommendation.domain.RecommendedBundle
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.domain.combination.ListCandidate
import com.bunch.recommendation.domain.combination.RecommendedListRules
import com.bunch.recommendation.dto.RecommendedBundleCard
import com.bunch.recommendation.dto.RecommendedBundleDetailResponse
import com.bunch.recommendation.dto.RecommendedBundleListResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * The recommended bundle sheet made out of the recommended bundles of the new combination (#183): the cards of the list
 * (4.2.1, 4.2.2) and the photos of a card (4.2.5). It gives the same answer as the old recommended bundles
 * ([RecommendationService]) with the kind, the event and the activity added, and it is read only while the new
 * combination is switched on.
 *
 * - Only a bundle whose time to be shown from came is shown (10-4), in the list and in the detail.
 * - A bundle is shown as its card: the photos of the card, in the order of their places, the first one is the cover
 *   (the place 1). A photo that was deleted is already gone, and one whose date was cleared is left out until the next
 *   calculation. A bundle with no photo of its card left is left out of the list and is not found.
 * - The list is the one of [RecommendedListRules]: at most 20 bundles, at most 3 general bundles of an activity, in the
 *   order of 4.2.9 rule 6. The numbers of the order are the ones the card shows: the members who uploaded the photos of
 *   the card and the photos of the card.
 * - The end day is given only when the bundle was taken over midnight, as for the old ones.
 *
 * The list belongs to the bunch, so every member of it gets the same one. Whether the caller is a member of the bunch
 * is decided by the caller.
 */
@Service
class RecommendedBundleReader(
    private val bundles: RecommendedBundleRepository,
    private val recommendations: RecommendationRepository,
    private val parts: RecommendedCardParts,
    private val rules: RecommendedListRules,
    private val clock: Clock
) {
    @Transactional(readOnly = true)
    fun cards(bunchId: Long): RecommendedBundleListResponse {
        val visible = bundles.findVisible(bunchId, clock.instant())
        if (visible.isEmpty()) return RecommendedBundleListResponse(emptyList())

        val cardRows = recommendations.findRecommendedCardPhotoRows(visible.map { it.id }).groupBy { it.candidateId }
        val uploadersOf = parts.uploadersOf(cardRows.values.flatten().map { it.photoId })
        val withCard = visible.filter { it.id in cardRows }
        val chips = withCard.associate { bundle ->
            bundle.id to parts.chipsOf(cardRows.getValue(bundle.id).map { uploadersOf[it.photoId].orEmpty() })
        }
        val candidates = withCard.map { bundle ->
            ListCandidate(
                id = bundle.id,
                kind = bundle.kind,
                dayKey = bundle.dayKey,
                startAt = bundle.startAt,
                activity = bundle.activity,
                hasGroupPhoto = bundle.hasGroupPhoto,
                lastPhotoAddedAt = bundle.lastPhotoAddedAt,
                uploaderCount = chips.getValue(bundle.id).size,
                photoCount = cardRows.getValue(bundle.id).size
            )
        }

        val shown = rules.select(candidates)
        val ranges = recommendations.findRecommendedDateRanges(shown).associateBy { it.bundleId }
        val byId = withCard.associateBy { it.id }
        return RecommendedBundleListResponse(
            shown.map { id ->
                val bundle = byId.getValue(id)
                val photos = cardRows.getValue(id)
                val startDate = ranges[id]?.startDate ?: photos.first().capturedDate
                RecommendedBundleCard(
                    id = bundle.id,
                    title = bundle.title,
                    startDate = startDate,
                    endDate = ranges[id]?.endDate?.takeIf { it != startDate },
                    photoCount = photos.size,
                    uploaders = chips.getValue(id),
                    coverImageUrl = PhotoImageUrls.full(photos.first().imageUrl),
                    kind = bundle.kind.name,
                    eventType = bundle.eventType?.name,
                    activity = bundle.activity?.name
                )
            }
        )
    }

    /** A bundle that is gone, not shown yet, of another bunch or with no card photo left is not found (4.2.5). */
    @Transactional(readOnly = true)
    fun photos(bunchId: Long, bundleId: Long): RecommendedBundleDetailResponse {
        val bundle = bundles.findByIdAndBunchId(bundleId, bunchId)?.takeIf(::isShown)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val rows = recommendations.findRecommendedCardPhotoRows(listOf(bundleId))
        if (rows.isEmpty()) throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        val uploadersOf = parts.uploadersOf(rows.map { it.photoId })
        return RecommendedBundleDetailResponse(
            id = bundle.id,
            title = bundle.title,
            photos = rows.map { parts.photoOf(it, uploadersOf[it.photoId].orEmpty()) },
            kind = bundle.kind.name,
            eventType = bundle.eventType?.name,
            activity = bundle.activity?.name
        )
    }

    private fun isShown(bundle: RecommendedBundle) = !bundle.visibleFrom.isAfter(clock.instant())
}
