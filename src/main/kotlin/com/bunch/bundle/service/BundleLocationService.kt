package com.bunch.bundle.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.domain.BundleLocation
import com.bunch.bundle.domain.BundleLocationRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.bundle.dto.BundleLocationChange
import com.bunch.bundle.dto.BundleLocationInput
import com.bunch.bundle.dto.BundleLocationResponse
import com.bunch.bundle.dto.BundleLocationState
import com.bunch.bundle.dto.BundleLocationsRequest
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.place.domain.PlaceDetails
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class BundleLocationService(
    private val access: BundleAccessService,
    private val feedAccess: FeedAccessService,
    private val bundles: BundleRepository,
    private val locations: BundleLocationRepository,
    private val memberships: BunchMembershipRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val leases: BundleLeaseService
) {
    @Transactional
    fun saveDraft(
        actor: BundleActor,
        bunchId: Long,
        bundleId: Long,
        request: BundleLocationsRequest,
        requestId: String
    ): BundleLocationState {
        val bundle = access.lock(actor.memberId, bunchId, bundleId)
        access.requireParticipant(actor.memberId, bundle)
        leases.requireLease(bundle, actor, "LOCATION", request.leaseToken)
        if (request.expectedVersion != bundle.locationVersion) {
            fail(ErrorCode.BUNDLE_VERSION_CONFLICT, mapOf(
                "locations" to listForBundles(listOf(bundle.id)).getValue(bundle.id),
                "version" to bundle.locationVersion
            ))
        }
        val result = replaceLocked(bundle, request.locations)
        if (result.changed) {
            bundle.revision++
            bundle.updatedAt = clock.instant()
            memberships.findByMemberIdAndBunchIdAndStatus(actor.memberId, bunchId)
                ?.recordActivity(bundle.updatedAt)
            events.publishEvent(BundleChangedEvent(
                bunchId, bundle.id, bundle.revision, requestId, BundleEventType.LOCATION,
                mapOf("locations" to result.locations, "version" to bundle.locationVersion)
            ))
        }
        return BundleLocationState(bundle.id, bundle.revision, bundle.locationVersion, result.locations)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(userId: Long, bunchId: Long, bundleId: Long): BundleLocationState {
        // Internal draft access and public published access have separate policies.
        val bundle = bundles.findById(bundleId).orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
        if (bundle.bunchId != bunchId) fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (bundle.status == BundleStatus.PUBLISHED) feedAccess.readPublishedBundle(userId, bundleId)
        else access.read(userId, bunchId, bundleId)
        return BundleLocationState(
            bundle.id, bundle.revision, bundle.locationVersion, listForBundles(listOf(bundle.id)).getValue(bundle.id)
        )
    }

    /** Internal batch query. Caller must authorize each bundle first; no user input may bypass that guard. */
    @Transactional(readOnly = true)
    fun listForBundles(bundleIds: Collection<Long>): Map<Long, List<BundleLocationResponse>> {
        if (bundleIds.size > 500 || bundleIds.any { it <= 0 }) fail(ErrorCode.INVALID_INPUT)
        if (bundleIds.isEmpty()) return emptyMap()
        val rows = locations.findAllByBundleIdInOrderByBundleIdAscPositionAscIdAsc(bundleIds)
            .groupBy { it.bundleId }
        return bundleIds.associateWith { id -> rows[id].orEmpty().map(::response) }
    }

    /**
     * C's atomic published editor must authorize OWNER and lock bunch -> bundle before calling.
     * Only locationVersion changes here. The caller owns aggregate revision/time/event and all other fields.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun replaceLocked(bundle: Bundle, input: List<BundleLocationInput>): BundleLocationChange {
        val normalized = normalize(input)
        val current = locations.findAllByBundleIdOrderByPositionAscIdAsc(bundle.id)
        val before = current.map(::response)
        val keys = normalized.map { it.first.provider to it.first.externalId }.toSet()
        val removed = current.filter { (it.provider to it.externalId) !in keys }
        if (removed.isNotEmpty()) {
            // A synchronous pin listener deletes only these source references before FK SET NULL can run.
            events.publishEvent(BundleLocationsRemovedEvent(bundle.id, removed.map { it.id }))
            locations.deleteAll(removed)
            locations.flush()
        }
        val byKey = current.associateBy { it.provider to it.externalId }
        val after = normalized.map { (place, position) ->
            val row = byKey[place.provider to place.externalId] ?: locations.save(BundleLocation(
                bundleId = bundle.id,
                provider = place.provider,
                externalId = place.externalId,
                name = place.name,
                address = place.address,
                position = position
            ))
            row.name = place.name
            row.address = place.address
            row.position = position
            response(row)
        }
        val changed = before != after
        if (changed) bundle.locationVersion++
        // Deferred position uniqueness allows swaps without deleting/recreating stable source IDs.
        locations.flush()
        return BundleLocationChange(changed, after)
    }

    private fun normalize(input: List<BundleLocationInput>): List<Pair<PlaceDetails, Int>> {
        if (input.size > 3 || input.map { it.position }.sorted() != input.indices.toList()) {
            fail(ErrorCode.INVALID_INPUT)
        }
        val result = try {
            input.sortedBy { it.position }.map {
                PlaceDetails.of(it.provider, it.externalId, it.name, it.address) to it.position
            }
        } catch (_: IllegalArgumentException) {
            fail(ErrorCode.INVALID_INPUT)
        }
        if (result.map { it.first.provider to it.first.externalId }.distinct().size != result.size) {
            fail(ErrorCode.INVALID_INPUT)
        }
        return result
    }

    private fun response(row: BundleLocation) = BundleLocationResponse(
        row.id, row.provider, row.externalId, row.name, row.address, row.position
    )

    private fun fail(code: ErrorCode, detail: Any? = null): Nothing = throw BusinessException(code, detail)
}
