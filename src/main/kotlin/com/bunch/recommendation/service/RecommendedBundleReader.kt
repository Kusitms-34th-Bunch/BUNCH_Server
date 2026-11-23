package com.bunch.recommendation.service

import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.PhotoImageUrls
import com.bunch.recommendation.domain.RecommendationRepository
import com.bunch.recommendation.domain.RecommendationRepository.CardPhotoRow
import com.bunch.recommendation.domain.RecommendedBundle
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.domain.UsedCandidate
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.ListCandidate
import com.bunch.recommendation.domain.combination.RecommendedListRules
import com.bunch.recommendation.dto.RecommendedBundleCard
import com.bunch.recommendation.dto.RecommendedBundleDetailResponse
import com.bunch.recommendation.dto.RecommendedBundleListResponse
import com.bunch.recommendation.dto.RecommendedBundlePhoto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Shows the new recommended bundles (the ones of the combination, `recommended_bundles`) and lets a draft be made from
 * one: the list of the cards, the photos of one card, and the taking of a bundle by a draft (photo box 4.2.1 to 4.2.5,
 * 4.1.3, 4.1.4). [RecommendationService] hands the three over to it while `reco.combination.enabled` is on; while it is
 * off the old recommended bundles are shown, as before.
 *
 * A bundle is shown from the time it is made visible (the day is over, X6) and until it is deleted (30 days after its
 * last photo was added, 4.2.9 rule 4). Every member of the bunch gets the same list. The dates of a bundle are those of
 * the clock it was taken on, in the zone of the settings: the date it starts is its date (D7), and it has an end date
 * when it goes past midnight.
 *
 * The cover is the photo that stands for the card and for the bundle a draft is made from (4.2.2, 4.1.4): it is the
 * first of the photos that are given, and the other photos follow in the order of the card, which is the order of the
 * time. A bundle that has no cover (its photo was deleted and the day is not calculated again yet) gives its photos in
 * the order of the time.
 */
@Service
class RecommendedBundleReader(
    private val bundles: RecommendedBundleRepository,
    private val recommendations: RecommendationRepository,
    private val uploaders: RecommendedUploaders,
    private val accessNotices: BunchAccessNoticeService,
    private val settings: CombinationSettings,
    private val clock: Clock
) {
    private val rules = RecommendedListRules(settings)

    /**
     * The cards of the sheet of a bunch in the order of the list (4.2.1, 4.2.9 rule 6). A bundle with no photo of its
     * card left is left out: its photos were all deleted and the next calculation will delete the bundle. The count is
     * the photos that are left, so it can be under six until then.
     */
    @Transactional(readOnly = true)
    fun cards(userId: Long, bunchId: Long): RecommendedBundleListResponse {
        accessNotices.requireMemberOrAccessNotice(userId, bunchId)
        val shown = bundles.findByBunchIdAndVisibleFromLessThanEqual(bunchId, clock.instant())
        if (shown.isEmpty()) return RecommendedBundleListResponse(emptyList())

        val photosOf = recommendations.findRecommendedCardPhotoRows(shown.map { it.id }).groupBy { it.candidateId }
        val uploadersOf = uploaders.of(photosOf.values.flatten().map { it.photoId })
        val candidates = shown.mapNotNull { bundle ->
            val photos = photosOf[bundle.id] ?: return@mapNotNull null
            ListCandidate(
                id = bundle.id,
                lastPhotoAddedAt = bundle.lastPhotoAddedAt,
                uploaderCount = photos.flatMap { uploadersOf[it.photoId].orEmpty() }.map { it.memberId }.toSet().size,
                photoCount = photos.size
            )
        }
        val byId = shown.associateBy { it.id }
        val cards = rules.select(candidates).map { id ->
            val bundle = byId.getValue(id)
            val photos = photosOf.getValue(id)
            RecommendedBundleCard(
                id = bundle.id,
                title = bundle.title,
                startDate = startDateOf(bundle),
                endDate = endDateOf(bundle),
                photoCount = photos.size,
                uploaders = uploaders.chipsOf(photos.map { uploadersOf[it.photoId].orEmpty() }),
                coverImageUrl = PhotoImageUrls.full(coverFirst(photos, bundle.coverPhotoId).first().imageUrl),
                startAt = bundle.startAt.atZone(settings.defaultZone).toOffsetDateTime(),
                endAt = bundle.endAt.atZone(settings.defaultZone).toOffsetDateTime()
            )
        }
        return RecommendedBundleListResponse(cards)
    }

    /**
     * The photos of a bundle in the order they are applied in (4.2.3, 4.2.5): the cover first, then the others in the
     * order of the time. The photos that were deleted are not there. A bundle that was deleted, one that is not shown
     * yet, or one of another bunch is not found, and the app tells the member and reloads the sheet (4.2.5).
     */
    @Transactional(readOnly = true)
    fun photos(userId: Long, bunchId: Long, id: Long): RecommendedBundleDetailResponse {
        accessNotices.requireMemberOrAccessNotice(userId, bunchId)
        val bundle = shownBundle(bunchId, id) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val rows = recommendations.findRecommendedCardPhotoRows(listOf(bundle.id))
        if (rows.isEmpty()) throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        val ordered = coverFirst(rows, bundle.coverPhotoId)
        val uploadersOf = uploaders.of(ordered.map { it.photoId })
        val photos = ordered.map { row ->
            RecommendedBundlePhoto(
                photoId = row.photoId,
                thumbnailUrl = PhotoImageUrls.thumbnail(row.imageUrl),
                imageUrl = PhotoImageUrls.full(row.imageUrl),
                capturedDate = row.capturedDate,
                capturedTime = row.capturedTime,
                uploaders = uploadersOf[row.photoId].orEmpty()
            )
        }
        return RecommendedBundleDetailResponse(bundle.id, bundle.title, photos)
    }

    /**
     * Takes the bundle a draft is made from (4.1.3 rules 5 and 8, 4.2.9 rule 5). The bundle is deleted and its photos
     * are noted as used, so the photos the draft does not take only become a new recommended bundle when a photo is
     * added to them. The draft gets its title and its first day. Returns null when the bundle is not there any more,
     * is not shown yet, or belongs to another bunch; the draft is made without it then. The caller is the creation of
     * the draft, which has locked the bunch, so two drafts never take the same bundle.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun use(bunchId: Long, id: Long): UsedCandidate? {
        val bundle = shownBundle(bunchId, id) ?: return null
        recommendations.markRecommendedBundlePhotosUsed(bunchId, bundle.id)
        bundles.delete(bundle)
        return UsedCandidate(bundle.title, startDateOf(bundle))
    }

    private fun shownBundle(bunchId: Long, id: Long): RecommendedBundle? =
        bundles.findByIdAndBunchId(id, bunchId)?.takeIf { !it.visibleFrom.isAfter(clock.instant()) }

    // The cover first, the others after it in the order they come in. Without a cover the order stays.
    private fun coverFirst(photos: List<CardPhotoRow>, coverPhotoId: Long?): List<CardPhotoRow> {
        val cover = photos.firstOrNull { it.photoId == coverPhotoId } ?: return photos
        return listOf(cover) + photos.filter { it !== cover }
    }

    private fun startDateOf(bundle: RecommendedBundle): LocalDate =
        bundle.startAt.atZone(settings.defaultZone).toLocalDate()

    // Set only for a bundle that goes past midnight (4.2.2).
    private fun endDateOf(bundle: RecommendedBundle): LocalDate? =
        bundle.endAt.atZone(settings.defaultZone).toLocalDate().takeIf { it != startDateOf(bundle) }
}
