package com.bunch.bundle.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.domain.BundleLocation
import com.bunch.bundle.domain.BundleLocationRepository
import com.bunch.bundle.domain.BundleRequest
import com.bunch.bundle.domain.BundleRequestRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.bundle.dto.BundleLocationChange
import com.bunch.bundle.dto.BundleLocationInput
import com.bunch.bundle.domain.BundleLocationIntent
import com.bunch.bundle.dto.BundleLocationIntentPlace
import com.bunch.bundle.dto.BundleLocationIntentRequest
import com.bunch.bundle.dto.BundleLocationIntentResponse
import com.bunch.bundle.dto.BundleLocationResponse
import com.bunch.bundle.dto.BundleLocationState
import com.bunch.bundle.dto.BundleLocationsRemovedEvent
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
import java.util.UUID

@Service
class BundleLocationService(
    private val access: BundleAccessService,
    private val feedAccess: FeedAccessService,
    private val bundles: BundleRepository,
    private val locations: BundleLocationRepository,
    private val requests: BundleRequestRepository,
    private val memberships: BunchMembershipRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock
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
        // The legacy whole-list request keeps leaseToken for wire compatibility, but this
        // fallback is protected by the locked bundle and expectedVersion instead of a lease.
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

    @Transactional
    fun applyIntent(
        actor: BundleActor,
        bunchId: Long,
        bundleId: Long,
        request: BundleLocationIntentRequest,
        requestId: String
    ): BundleLocationIntentResponse {
        val bundle = access.lock(actor.memberId, bunchId, bundleId)
        access.requireParticipant(actor.memberId, bundle)
        val current = locations.findAllByBundleIdOrderByPositionAscIdAsc(bundle.id)
        validateIntent(bundle, request)
        val plan = plan(request, current)
        val result = replaceLocked(bundle, plan.inputs)
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
        return BundleLocationIntentResponse(
            bundleId = bundle.id,
            revision = bundle.revision,
            locationVersion = bundle.locationVersion,
            changed = result.changed,
            acceptedCount = if (result.changed) plan.acceptedCount else 0,
            locations = result.locations
        )
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
            removed.forEach { row ->
                // Retain only ID provenance, using the existing per-bundle request storage.
                // A deleted target must remain distinguishable from an unknown/foreign ID.
                requests.save(BundleRequest(
                    bunchId = bundle.bunchId,
                    memberId = bundle.createdBy,
                    operation = REMOVED_LOCATION,
                    requestKey = UUID(0, row.id),
                    fingerprint = row.id.toString(),
                    bundleId = bundle.id,
                    revision = bundle.revision,
                    createdAt = clock.instant()
                ))
            }
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

    private fun validateIntent(bundle: Bundle, request: BundleLocationIntentRequest) {
        if (request.locations.size > MAX_LOCATIONS) fail(ErrorCode.INVALID_INPUT)
        when (request.intent) {
            BundleLocationIntent.ADD -> {
                if (request.locations.isEmpty() || request.location != null || request.targetLocationId != null ||
                    request.previousLocationId != null || request.nextLocationId != null) fail(ErrorCode.INVALID_INPUT)
            }
            BundleLocationIntent.REPLACE -> {
                if (request.location == null || request.locations.isNotEmpty() ||
                    request.previousLocationId != null || request.nextLocationId != null) fail(ErrorCode.INVALID_INPUT)
                requireKnownLocation(bundle, positive(request.targetLocationId))
            }
            BundleLocationIntent.REMOVE -> {
                if (request.location != null || request.locations.isNotEmpty() ||
                    request.previousLocationId != null || request.nextLocationId != null) fail(ErrorCode.INVALID_INPUT)
                requireKnownLocation(bundle, positive(request.targetLocationId))
            }
            BundleLocationIntent.MOVE -> {
                if (request.location != null || request.locations.isNotEmpty()) fail(ErrorCode.INVALID_INPUT)
                val target = positive(request.targetLocationId)
                val previous = request.previousLocationId?.let(::positive)
                val next = request.nextLocationId?.let(::positive)
                if (previous == target || next == target || (previous != null && previous == next)) {
                    fail(ErrorCode.INVALID_INPUT)
                }
                listOfNotNull(target, previous, next).forEach { requireKnownLocation(bundle, it) }
            }
        }
    }

    private fun requireKnownLocation(bundle: Bundle, id: Long) {
        val row = locations.findById(id).orElse(null)
        if (row != null) {
            if (row.bundleId != bundle.id) fail(ErrorCode.RESOURCE_NOT_FOUND)
            return
        }
        val removed = requests.findByBunchIdAndMemberIdAndOperationAndRequestKey(
            bundle.bunchId, bundle.createdBy, REMOVED_LOCATION, UUID(0, id)
        )
        if (removed?.bundleId != bundle.id) fail(ErrorCode.RESOURCE_NOT_FOUND)
    }

    private fun plan(
        request: BundleLocationIntentRequest,
        current: List<BundleLocation>
    ): IntentPlan = when (request.intent) {
        BundleLocationIntent.ADD -> addPlan(request, current)
        BundleLocationIntent.REPLACE -> replacePlan(request, current)
        BundleLocationIntent.REMOVE -> removePlan(request, current)
        BundleLocationIntent.MOVE -> movePlan(request, current)
    }

    private fun addPlan(
        request: BundleLocationIntentRequest,
        current: List<BundleLocation>
    ): IntentPlan {
        if (request.locations.isEmpty()) fail(ErrorCode.INVALID_INPUT)
        val normalized = normalizePlaces(request.locations)
        val currentKeys = current.map(::key).toSet()
        val additions = normalized
            .filterNot { it.key() in currentKeys }
            .take(MAX_LOCATIONS - current.size)
        val drafts = current.map(::draft).toMutableList()
        additions.forEach { drafts.add(LocationDraft(null, it)) }
        return IntentPlan(toInputs(drafts), additions.size)
    }

    private fun replacePlan(
        request: BundleLocationIntentRequest,
        current: List<BundleLocation>
    ): IntentPlan {
        val targetId = positive(request.targetLocationId)
        val replacement = request.location?.let(::normalizePlace) ?: fail(ErrorCode.INVALID_INPUT)
        if (current.any { key(it) == replacement.key() }) {
            return IntentPlan(toInputs(current.map(::draft)), 0)
        }
        val targetIndex = current.indexOfFirst { it.id == targetId }
        if (targetIndex < 0) {
            if (current.size >= MAX_LOCATIONS) {
                return IntentPlan(toInputs(current.map(::draft)), 0)
            }
            val drafts = current.map(::draft).toMutableList()
            drafts.add(LocationDraft(null, replacement))
            return IntentPlan(toInputs(drafts), 1)
        }

        val drafts = current.map(::draft).toMutableList()
        drafts.removeAt(targetIndex)
        drafts.add(targetIndex.coerceAtMost(drafts.size), LocationDraft(null, replacement))
        return IntentPlan(toInputs(drafts), 1)
    }

    private fun removePlan(
        request: BundleLocationIntentRequest,
        current: List<BundleLocation>
    ): IntentPlan {
        val targetId = positive(request.targetLocationId)
        if (current.none { it.id == targetId }) return IntentPlan(toInputs(current.map(::draft)), 0)
        return IntentPlan(toInputs(current.filterNot { it.id == targetId }.map(::draft)), 1)
    }

    private fun movePlan(
        request: BundleLocationIntentRequest,
        current: List<BundleLocation>
    ): IntentPlan {
        val targetId = positive(request.targetLocationId)
        val previousId = request.previousLocationId?.also { positive(it) }
        val nextId = request.nextLocationId?.also { positive(it) }
        if (previousId == targetId || nextId == targetId) fail(ErrorCode.INVALID_INPUT)
        val target = current.firstOrNull { it.id == targetId }
            ?: return IntentPlan(toInputs(current.map(::draft)), 0)
        val rest = current.filterNot { it.id == targetId }.toMutableList()
        val nextIndex = nextId?.let { id -> rest.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        val previousIndex = previousId?.let { id ->
            rest.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }
        val insertionIndex = nextIndex ?: previousIndex?.plus(1) ?: rest.size
        rest.add(insertionIndex.coerceIn(0, rest.size), target)
        val changed = rest.map { it.id } != current.map { it.id }
        return IntentPlan(toInputs(rest.map(::draft)), if (changed) 1 else 0)
    }

    private fun normalizePlaces(places: List<BundleLocationIntentPlace>): List<BundleLocationIntentPlace> =
        places.map(::normalizePlace).distinctBy { it.key() }

    private fun normalizePlace(place: BundleLocationIntentPlace): BundleLocationIntentPlace {
        val normalized = try {
            PlaceDetails.of(place.provider, place.externalId, place.name, place.address)
        } catch (_: IllegalArgumentException) {
            fail(ErrorCode.INVALID_INPUT)
        }
        return BundleLocationIntentPlace(
            normalized.provider,
            normalized.externalId,
            normalized.name,
            normalized.address
        )
    }

    private fun toInputs(drafts: List<LocationDraft>): List<BundleLocationInput> =
        drafts.mapIndexed { index, draft ->
            BundleLocationInput(
                provider = draft.place.provider,
                externalId = draft.place.externalId,
                name = draft.place.name,
                address = draft.place.address,
                position = index
            )
        }

    private fun draft(row: BundleLocation) = LocationDraft(
        id = row.id,
        place = BundleLocationIntentPlace(row.provider, row.externalId, row.name, row.address)
    )

    private fun key(row: BundleLocation) = row.provider to row.externalId

    private fun BundleLocationIntentPlace.key() = provider to externalId

    private fun positive(value: Long?): Long {
        if (value == null || value <= 0) fail(ErrorCode.INVALID_INPUT)
        return value
    }

    private fun response(row: BundleLocation) = BundleLocationResponse(
        row.id, row.provider, row.externalId, row.name, row.address, row.position
    )

    private data class IntentPlan(
        val inputs: List<BundleLocationInput>,
        val acceptedCount: Int
    )

    private data class LocationDraft(
        val id: Long?,
        val place: BundleLocationIntentPlace
    )

    private companion object {
        const val MAX_LOCATIONS = 3
        const val REMOVED_LOCATION = "LOCATION_REMOVED"
    }

    private fun fail(code: ErrorCode, detail: Any? = null): Nothing = throw BusinessException(code, detail)
}
