package com.bunch.kok.service

import com.bunch.bunch.domain.BunchAccessNoticeRepository
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.service.BundleLocationService
import com.bunch.feed.domain.FeedQueryRepository
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.kok.domain.Kok
import com.bunch.kok.domain.KokRepository
import com.bunch.kok.domain.KokSource
import com.bunch.place.domain.PlaceDetails
import com.bunch.kok.dto.BundleLocationKokResponse
import com.bunch.kok.dto.BundleKoksResponse
import com.bunch.kok.dto.KokResponse
import com.bunch.kok.dto.KokCardResponse
import com.bunch.kok.dto.KokMemberResponse
import com.bunch.kok.dto.KokCursor
import com.bunch.kok.dto.KoksResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

@Service
class KokService(
    private val access: FeedAccessService,
    private val members: MemberRepository,
    private val bunches: BunchRepository,
    private val bundles: BundleRepository,
    private val locations: BundleLocationService,
    private val pins: KokRepository,
    private val entityManager: EntityManager,
    private val memberships: BunchMembershipRepository,
    private val notices: BunchAccessNoticeRepository,
    private val feedQueries: FeedQueryRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(
        userId: Long,
        memberId: Long? = null,
        cursor: String? = null,
        size: Int = 20
    ): KoksResponse {
        if (size !in 1..50 || (memberId != null && memberId <= 0)) fail(ErrorCode.INVALID_INPUT)
        val bunchId = access.requireActor(userId).bunch.id
        val activeMembers = memberships.findActiveMembers(bunchId)
        val selectedMemberId = memberId?.takeIf { id -> activeMembers.any { it.member.id == id } }
        // An expired member chip returns to ALL from the beginning, not halfway through its old page.
        val start = cursor?.takeUnless { memberId != selectedMemberId }?.let {
            decodeCursor(it, userId, bunchId, selectedMemberId)
        }
        val rows = pins.page(
            bunchId, selectedMemberId, start != null, start?.pinnedAt, start?.id, PageRequest.of(0, size + 1)
        )
        val taken = rows.take(size)
        val counts = pins.countsByMember(bunchId)
        val byMember = counts.associate { it.memberId to it.count }
        val next = taken.lastOrNull()?.takeIf { rows.size > size }?.let {
            encodeCursor(KokCursor(
                userId = userId,
                bunchId = bunchId,
                memberId = selectedMemberId,
                pinnedAt = it.pinnedAt,
                id = it.id
            ))
        }
        return KoksResponse(
            bunchId = bunchId,
            selectedMemberId = selectedMemberId,
            totalCount = counts.sumOf { it.count },
            members = activeMembers.map {
                KokMemberResponse(
                    membershipId = it.id,
                    memberId = it.member.id,
                    nickname = it.member.nickname,
                    imageUrl = it.member.imageUrl,
                    count = byMember[it.member.id] ?: 0L
                )
            },
            items = cards(userId, taken),
            nextCursor = next
        )
    }

    private fun cards(userId: Long, rows: List<Kok>): List<KokCardResponse> {
        val candidateIds = rows.mapNotNull { it.sourceBundleId }.distinct()
        val visibleIds = access.visibleBundleIds(userId, candidateIds, forFeed = false)
        val unavailableBunchIds = if (visibleIds.isEmpty()) emptySet() else {
            notices.unavailableBunchIds(userId, clock.instant())
        }
        val sources = if (visibleIds.isEmpty()) emptyMap() else {
            bundles.findAllById(visibleIds)
                .filterNot { it.bunchId in unavailableBunchIds }
                .associateBy { it.id }
        }
        val sourceBunches = if (sources.isEmpty()) emptyMap() else {
            feedQueries.bunches(sources.values.map { it.bunchId }.distinct()).associateBy { it.id }
        }
        return rows.map { pin ->
            val source = sources[pin.sourceBundleId]
            val sourceBunch = source?.let { sourceBunches[it.bunchId] }
            KokCardResponse(
                id = pin.id,
                provider = pin.provider,
                externalId = pin.externalId,
                name = pin.name,
                address = pin.address,
                pinnedByMemberId = pin.pinnedByMemberId,
                pinnedAt = pin.pinnedAt,
                sourceType = pin.sourceType,
                sourceAccessible = sourceBunch != null,
                sourceBundleId = source?.id,
                sourceBunchId = sourceBunch?.id,
                sourceBunchName = sourceBunch?.name,
                sourceBundleTitle = source?.title
            )
        }
    }

    private fun encodeCursor(cursor: KokCursor): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(mapper.writeValueAsBytes(cursor))

    private fun decodeCursor(token: String, userId: Long, bunchId: Long, memberId: Long?): KokCursor {
        val decoded = try {
            if (token.isBlank() || token.length > 2048) fail(ErrorCode.INVALID_INPUT)
            mapper.readValue(Base64.getUrlDecoder().decode(token), KokCursor::class.java)
        } catch (_: Exception) {
            fail(ErrorCode.INVALID_INPUT)
        }
        if (decoded.version != 1 || decoded.userId != userId || decoded.bunchId != bunchId ||
            decoded.memberId != memberId || decoded.id <= 0 ||
            decoded.pinnedAt < Instant.parse("0001-01-15T00:00:00Z") || decoded.pinnedAt > clock.instant() ||
            decoded.pinnedAt.nano % 1000 != 0
        ) fail(ErrorCode.INVALID_INPUT)
        return decoded
    }

    @Transactional(readOnly = true)
    fun forBundle(userId: Long, bundleId: Long): BundleKoksResponse {
        val bundle = access.readPublishedBundle(userId, bundleId)
        val viewer = access.viewer(userId)
        val rows = locations.listForBundles(listOf(bundle.id)).getValue(bundle.id)
        val existing = viewer.currentBunchId?.let { bunchId ->
            findForPlaces(bunchId, rows.map { PlaceDetails.of(it.provider, it.externalId, it.name, it.address) })
                .associateBy { it.provider to it.externalId }
        }.orEmpty()
        return BundleKoksResponse(bundle.id, viewer.currentBunchId, viewer.currentBunchId != null, rows.map {
            val pinId = existing[it.provider to it.externalId]?.id
            BundleLocationKokResponse(
                it.id, it.provider, it.externalId, it.name, it.address, it.position, pinId != null, pinId
            )
        })
    }

    @Transactional
    fun pinBundleLocation(userId: Long, bundleId: Long, locationId: Long): KokResponse {
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
        ), KokSource.BUNDLE, bundleId = bundleId, locationId = location.id)
    }

    /** H must lock and validate its actual advertisement row in this transaction before calling. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun pinAdvertisement(userId: Long, advertisementId: Long, place: PlaceDetails): KokResponse {
        positive(advertisementId)
        val actor = lockActor(userId)
        // Revalidate because data classes can be copied outside their private constructor.
        val validated = try {
            PlaceDetails.of(place.provider, place.externalId, place.name, place.address)
        } catch (_: IllegalArgumentException) {
            fail(ErrorCode.INVALID_INPUT)
        }
        return save(actor, validated, KokSource.ADVERTISEMENT, advertisementId = advertisementId)
    }

    @Transactional
    fun unpin(userId: Long, pinId: Long) {
        positive(pinId)
        val actor = lockActor(userId)
        pins.deleteOwned(pinId, actor.bunch.id)
    }

    /** Internal batch API; caller authorizes the bunch and each place before assembling a response. */
    @Transactional(readOnly = true)
    fun findForPlaces(bunchId: Long, places: Collection<PlaceDetails>): List<KokResponse> {
        positive(bunchId)
        if (places.size > 500) fail(ErrorCode.INVALID_INPUT)
        if (places.isEmpty()) return emptyList()
        val keys = places.map { "${it.provider}:${it.externalId}" }.distinct()
        return pins.findForKeys(bunchId, keys).map(KokResponse::from)
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
        source: KokSource,
        bundleId: Long? = null,
        locationId: Long? = null,
        advertisementId: Long? = null
    ): KokResponse {
        val existing = pins.findByBunchIdAndProviderAndExternalId(actor.bunch.id, place.provider, place.externalId)
        if (existing != null) return KokResponse.from(existing)
        val pin = pins.saveAndFlush(Kok(
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
        return KokResponse.from(pin)
    }

    private fun positive(vararg ids: Long) {
        if (ids.any { it <= 0 }) fail(ErrorCode.INVALID_INPUT)
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
