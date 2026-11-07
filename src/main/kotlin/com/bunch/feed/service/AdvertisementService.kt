package com.bunch.feed.service

import com.bunch.bunch.domain.BunchRepository
import com.bunch.feed.domain.Advertisement
import com.bunch.feed.domain.AdvertisementRepository
import com.bunch.feed.dto.AdvertisementBatchResponse
import com.bunch.feed.dto.AdvertisementPlacement
import com.bunch.feed.dto.AdvertisementResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.place.dto.PinnedPlaceResponse
import com.bunch.place.service.PinnedPlaceService
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdvertisementService(
    private val advertisements: AdvertisementRepository,
    private val access: FeedAccessService,
    private val members: MemberRepository,
    private val bunches: BunchRepository,
    private val entityManager: EntityManager,
    private val selector: AdvertisementSelector,
    private val pins: PinnedPlaceService
) {
    @Transactional(readOnly = true)
    fun selectForFeed(
        userId: Long,
        bundleCount: Int,
        slots: List<Int>,
        lastAdId: Long?
    ): AdvertisementBatchResponse {
        if (bundleCount !in 0..50 || slots.any { it !in 1..bundleCount } ||
            slots.zipWithNext().any { (left, right) -> left >= right } ||
            (lastAdId != null && lastAdId <= 0)
        ) fail(ErrorCode.INVALID_INPUT)
        val viewer = access.viewer(userId)
        if (bundleCount == 0 || slots.isEmpty()) return AdvertisementBatchResponse(emptyList(), lastAdId)
        val candidates = advertisements.findEligible(userId).associateBy { it.id }
        val selected = selector.select(candidates.keys.toList(), slots.size, lastAdId)
        val selectedRows = selected.filterNotNull().distinct().map { candidates.getValue(it) }
        val cards = responses(selectedRows, viewer.currentBunchId)
        val placements = slots.zip(selected).mapNotNull { (afterBundle, id) ->
            id?.let { AdvertisementPlacement(afterBundle, cards.getValue(it)) }
        }
        return AdvertisementBatchResponse(placements, selected.lastOrNull { it != null } ?: lastAdId)
    }

    @Transactional(readOnly = true)
    fun read(userId: Long, advertisementId: Long): AdvertisementResponse {
        positive(advertisementId)
        val viewer = access.viewer(userId)
        val advertisement = advertisements.findById(advertisementId)
            .orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
        requireVisible(userId, advertisement)
        return responses(listOf(advertisement), viewer.currentBunchId).getValue(advertisementId)
    }

    @Transactional
    fun pin(userId: Long, advertisementId: Long): PinnedPlaceResponse {
        positive(advertisementId)
        // Lock in member -> current bunch -> advertisement order; G reenters the first two locks.
        lockMember(userId)
        val actorBunchId = access.requireActor(userId).bunch.id
        val bunch = bunches.findLocked(actorBunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        entityManager.refresh(bunch)
        if (access.requireActor(userId).bunch.id != actorBunchId) fail(ErrorCode.FORBIDDEN)
        val advertisement = locked(advertisementId)
        requireVisible(userId, advertisement)
        return pins.pinAdvertisement(userId, advertisement.id, advertisement.place())
    }

    @Transactional
    fun hide(userId: Long, advertisementId: Long) {
        positive(advertisementId)
        lockMember(userId)
        locked(advertisementId)
        advertisements.hide(userId, advertisementId)
    }

    private fun responses(rows: List<Advertisement>, currentBunchId: Long?): Map<Long, AdvertisementResponse> {
        if (rows.isEmpty()) return emptyMap()
        val places = rows.associate { it.id to it.place() }
        val existing = currentBunchId?.let { bunchId ->
            pins.findForPlaces(bunchId, places.values.distinctBy { it.provider to it.externalId })
                .associateBy { it.provider to it.externalId }
        }.orEmpty()
        return rows.associate { row ->
            val place = places.getValue(row.id)
            val pinId = existing[place.provider to place.externalId]?.id
            row.id to AdvertisementResponse(
                id = row.id,
                name = row.name,
                introduction = row.introduction,
                photoUrl = row.photoUrl,
                tags = row.tags,
                externalLink = row.externalLink,
                place = place,
                pinned = pinId != null,
                pinId = pinId,
                canPin = currentBunchId != null
            )
        }
    }

    private fun requireVisible(userId: Long, advertisement: Advertisement) {
        if (!advertisement.active || advertisements.isHidden(userId, advertisement.id)) {
            fail(ErrorCode.RESOURCE_NOT_FOUND)
        }
    }

    private fun lockMember(userId: Long) {
        val member = members.findLockedById(userId) ?: fail(ErrorCode.USER_NOT_FOUND)
        entityManager.refresh(member)
        member.requireCompleted()
    }

    private fun locked(advertisementId: Long): Advertisement {
        val advertisement = advertisements.findLocked(advertisementId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        entityManager.refresh(advertisement)
        return advertisement
    }

    private fun positive(id: Long) {
        if (id <= 0) fail(ErrorCode.INVALID_INPUT)
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
