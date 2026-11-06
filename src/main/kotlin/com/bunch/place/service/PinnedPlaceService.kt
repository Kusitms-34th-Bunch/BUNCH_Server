package com.bunch.place.service

import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.service.BundleLocationService
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.place.domain.PinnedPlace
import com.bunch.place.domain.PinnedPlaceRepository
import com.bunch.place.domain.PinnedPlaceSource
import com.bunch.place.domain.PlaceDetails
import com.bunch.place.dto.BundlePlacePinResponse
import com.bunch.place.dto.BundlePlacePinsResponse
import com.bunch.place.dto.PinnedPlaceResponse
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class PinnedPlaceService(
    private val access: FeedAccessService,
    private val members: MemberRepository,
    private val bunches: BunchRepository,
    private val bundles: BundleRepository,
    private val locations: BundleLocationService,
    private val pins: PinnedPlaceRepository,
    private val entityManager: EntityManager,
    private val clock: Clock
) {
    @Transactional(readOnly = true)
    fun forBundle(userId: Long, bundleId: Long): BundlePlacePinsResponse {
        val bundle = access.readPublishedBundle(userId, bundleId)
        val viewer = access.viewer(userId)
        val rows = locations.listForBundles(listOf(bundle.id)).getValue(bundle.id)
        val existing = viewer.currentBunchId?.let { bunchId ->
            findForPlaces(bunchId, rows.map { PlaceDetails.of(it.provider, it.externalId, it.name, it.address) })
                .associateBy { it.provider to it.externalId }
        }.orEmpty()
        return BundlePlacePinsResponse(bundle.id, viewer.currentBunchId, viewer.currentBunchId != null, rows.map {
            val pinId = existing[it.provider to it.externalId]?.id
            BundlePlacePinResponse(
                it.id, it.provider, it.externalId, it.name, it.address, it.position, pinId != null, pinId
            )
        })
    }

    @Transactional
    fun pinBundleLocation(userId: Long, bundleId: Long, locationId: Long): PinnedPlaceResponse {
        positive(bundleId, locationId)
        lockMember(userId)
        val actorId = access.requireActor(userId).bunch.id
        val sourceBunchId = bundles.findBunchId(bundleId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        lockBunches(listOf(actorId, sourceBunchId))
        val bundle = bundles.findLocked(bundleId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        entityManager.refresh(bundle)
        val interaction = access.requireInteraction(userId, bundleId)
        if (interaction.actor.bunch.id != actorId) fail(ErrorCode.FORBIDDEN)
        val location = locations.listForBundles(listOf(bundleId)).getValue(bundleId)
            .firstOrNull { it.id == locationId } ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        return save(interaction.actor, PlaceDetails.of(
            location.provider, location.externalId, location.name, location.address
        ), PinnedPlaceSource.BUNDLE, bundleId = bundleId, locationId = location.id)
    }

    /** H must lock and validate its actual advertisement row in this transaction before calling. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun pinAdvertisement(userId: Long, advertisementId: Long, place: PlaceDetails): PinnedPlaceResponse {
        positive(advertisementId)
        val actor = lockActor(userId)
        // Revalidate because data classes can be copied outside their private constructor.
        val validated = try {
            PlaceDetails.of(place.provider, place.externalId, place.name, place.address)
        } catch (_: IllegalArgumentException) {
            fail(ErrorCode.INVALID_INPUT)
        }
        return save(actor, validated, PinnedPlaceSource.ADVERTISEMENT, advertisementId = advertisementId)
    }

    @Transactional
    fun unpin(userId: Long, pinId: Long) {
        positive(pinId)
        val actor = lockActor(userId)
        pins.deleteOwned(pinId, actor.bunch.id)
    }

    /** Internal batch API; caller authorizes the bunch and each place before assembling a response. */
    @Transactional(readOnly = true)
    fun findForPlaces(bunchId: Long, places: Collection<PlaceDetails>): List<PinnedPlaceResponse> {
        positive(bunchId)
        if (places.size > 500) fail(ErrorCode.INVALID_INPUT)
        if (places.isEmpty()) return emptyList()
        val keys = places.map { "${it.provider}:${it.externalId}" }.distinct()
        return pins.findForKeys(bunchId, keys).map(PinnedPlaceResponse::from)
    }

    private fun lockMember(userId: Long) {
        val member = members.findLockedById(userId) ?: fail(ErrorCode.USER_NOT_FOUND)
        entityManager.refresh(member)
        member.requireCompleted()
    }

    private fun lockActor(userId: Long): BunchMembership {
        lockMember(userId)
        val bunchId = access.requireActor(userId).bunch.id
        lockBunches(listOf(bunchId))
        return access.requireActor(userId).also {
            if (it.bunch.id != bunchId) fail(ErrorCode.FORBIDDEN)
        }
    }

    private fun lockBunches(ids: Collection<Long>) {
        ids.distinct().sorted().forEach { id ->
            val bunch = bunches.findLocked(id) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
            entityManager.refresh(bunch)
        }
    }

    private fun save(
        actor: BunchMembership,
        place: PlaceDetails,
        source: PinnedPlaceSource,
        bundleId: Long? = null,
        locationId: Long? = null,
        advertisementId: Long? = null
    ): PinnedPlaceResponse {
        val existing = pins.findByBunchIdAndProviderAndExternalId(actor.bunch.id, place.provider, place.externalId)
        if (existing != null) return PinnedPlaceResponse.from(existing)
        val pin = pins.saveAndFlush(PinnedPlace(
            bunchId = actor.bunch.id,
            provider = place.provider,
            externalId = place.externalId,
            name = place.name,
            address = place.address,
            sourceType = source,
            sourceBundleId = bundleId,
            sourceLocationId = locationId,
            sourceAdvertisementId = advertisementId,
            pinnedByMemberId = actor.member.id,
            pinnedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        ))
        return PinnedPlaceResponse.from(pin)
    }

    private fun positive(vararg ids: Long) {
        if (ids.any { it <= 0 }) fail(ErrorCode.INVALID_INPUT)
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
