package com.bunch.bundle.service

import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleEditLease
import com.bunch.bundle.domain.BundleEditLeaseRepository
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleLeaseStateChangedEvent
import com.bunch.bundle.dto.LeaseGrant
import com.bunch.bundle.dto.LeaseState
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class BundleLeaseService(
    private val access: BundleAccessService,
    private val bunches: BunchRepository,
    private val leases: BundleEditLeaseRepository,
    private val photos: BundlePhotoRepository,
    private val bundles: com.bunch.bundle.domain.BundleRepository,
    private val events: ApplicationEventPublisher
) {
    @Transactional
    fun acquire(
        actor: BundleActor,
        bunchId: Long,
        bundleId: Long,
        target: String,
        connectionId: String
    ): LeaseGrant {
        validateActor(actor)
        requireConnectionId(connectionId)
        val normalizedTarget = normalizeTarget(target)
        val bundle = access.lock(actor.memberId, bunchId, bundleId)
        access.requireParticipant(actor.memberId, bundle)
        requirePhotoBelongsToBundle(bundleId, normalizedTarget)

        val now = Instant.now()
        val lease = leases.findLockedByBundleIdAndTarget(bundleId, normalizedTarget)
        if (
            lease != null &&
            lease.isActive(now) &&
            !lease.isOwnedBy(actor.memberId, actor.sessionId, connectionId)
        ) {
            fail(ErrorCode.BUNDLE_LEASE_CONFLICT, publicState(lease))
        }

        val token = UUID.randomUUID()
        val stored = lease ?: BundleEditLease(
            bundleId = bundleId,
            target = normalizedTarget,
            memberId = actor.memberId,
            sessionId = actor.sessionId,
            connectionId = connectionId,
            token = token,
            version = 1,
            lastHeartbeatAt = now
        ).also(leases::save)
        if (lease != null) {
            stored.acquire(
                memberId = actor.memberId,
                sessionId = actor.sessionId,
                connectionId = connectionId,
                token = token,
                now = now
            )
        }
        val grant = grant(bundle, stored)
        publishStates(bundle)
        return grant
    }

    @Transactional
    fun heartbeat(
        actor: BundleActor,
        bunchId: Long,
        bundleId: Long,
        target: String,
        token: UUID,
        connectionId: String
    ): LeaseGrant {
        validateActor(actor)
        requireConnectionId(connectionId)
        val normalizedTarget = normalizeTarget(target)
        val bundle = access.lock(actor.memberId, bunchId, bundleId)
        access.requireParticipant(actor.memberId, bundle)
        requirePhotoBelongsToBundle(bundleId, normalizedTarget)

        val now = Instant.now()
        val lease = requireOwnedLease(bundle, actor, normalizedTarget, token, now, connectionId)
        lease.heartbeat(now)
        publishStates(bundle)
        return grant(bundle, lease)
    }

    @Transactional
    fun release(
        actor: BundleActor,
        bunchId: Long,
        bundleId: Long,
        target: String,
        token: UUID,
        connectionId: String
    ) {
        validateActor(actor)
        requireConnectionId(connectionId)
        val normalizedTarget = normalizeTarget(target)
        val bundle = access.lock(actor.memberId, bunchId, bundleId)
        access.requireParticipant(actor.memberId, bundle)
        requirePhotoBelongsToBundle(bundleId, normalizedTarget)

        val now = Instant.now()
        val lease = requireOwnedLease(bundle, actor, normalizedTarget, token, now, connectionId)
        lease.release(now)
        publishStates(bundle)
    }

    /**
     * Checks a lease while the caller owns the bundle write transaction.
     * The caller must lock the bunch and bundle first through BundleAccessService.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireLease(
        bundle: Bundle,
        actor: BundleActor,
        target: String,
        token: UUID?
    ) {
        validateActor(actor)
        val normalizedTarget = normalizeTarget(target)
        requirePhotoBelongsToBundle(bundle.id, normalizedTarget)
        access.requireParticipant(actor.memberId, bundle)
        if (token == null) fail(ErrorCode.BUNDLE_LEASE_CONFLICT)
        requireOwnedLease(bundle, actor, normalizedTarget, token, Instant.now())
    }

    /**
     * Rejects a photo operation only when another actor still owns that photo lease.
     * Move starts deliberately do not call this method; only the committed move does.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun rejectForeignPhoto(
        bundleId: Long,
        bundlePhotoId: Long,
        actor: BundleActor
    ) {
        validateActor(actor)
        val lease = leases.findLockedByBundleIdAndTarget(bundleId, photoTarget(bundlePhotoId)) ?: return
        if (lease.isActive(Instant.now()) && !lease.isOwnedBy(actor)) {
            fail(ErrorCode.BUNDLE_LEASE_CONFLICT, publicState(lease))
        }
    }

    @Transactional(readOnly = true)
    fun publicStates(bundleId: Long): List<LeaseState> {
        val now = Instant.now()
        return leases.findAllByBundleIdOrderByTargetAsc(bundleId)
            .filter { it.isActive(now) }
            .map { publicState(it) }
    }

    /** Releases every active lease for the bundle, preserving each row and its version. */
    @Transactional
    fun clear(bundleId: Long, memberId: Long? = null) {
        val now = Instant.now()
        val changed = leases.findAllLockedByBundleId(bundleId)
            .filter { memberId == null || it.memberId == memberId }
            .filter { it.releasedAt == null }
        changed.forEach { it.release(now) }
        publishStatesForBundleId(bundleId)
    }

    /** Releases only leases acquired through a disconnected STOMP connection. */
    @Transactional
    fun clearConnection(connectionId: String) {
        if (connectionId.isBlank()) return

        // This first query is deliberately non-locking. The disconnect path must not lock a
        // lease row before the normal bunch -> bundle lock order used by all bundle writes.
        // Scalar projections also prevent a pre-lock JPA entity snapshot from invalidating
        // a lease reacquired by a different connection while this transaction waited.
        val candidateBundleIds = leases.findBundleIdsByConnectionId(connectionId)

        val now = Instant.now()
        candidateBundleIds
            .mapNotNull { bundleId ->
                val bunchId = bundles.findBunchId(bundleId) ?: return@mapNotNull null
                bundleId to bunchId
            }
            .sortedWith(compareBy<Pair<Long, Long>> { it.second }.thenBy { it.first })
            .forEach { (bundleId, bunchId) ->
                // A disconnected user may have lost bunch membership already, so this path
                // locks the bunch directly instead of going through the access check.
                if (bunches.findLocked(bunchId) == null) return@forEach
                val bundle = bundles.findLocked(bundleId) ?: return@forEach
                if (bundle.bunchId != bunchId) return@forEach

                val changed = leases.findAllLockedByBundleId(bundle.id)
                    .filter { it.connectionId == connectionId }
                    .filter { it.releasedAt == null }
                changed.forEach { it.release(now) }
                if (changed.isNotEmpty()) publishStates(bundle)
            }
    }

    /** Invalidates a photo target without deleting the lease history row. */
    @Transactional
    fun clearPhoto(bundleId: Long, bundlePhotoId: Long) {
        val target = photoTarget(bundlePhotoId)
        val lease = leases.findLockedByBundleIdAndTarget(bundleId, target) ?: return
        lease.release(Instant.now())
        publishStatesForBundleId(bundleId)
    }

    private fun requireOwnedLease(
        bundle: Bundle,
        actor: BundleActor,
        target: String,
        token: UUID,
        now: Instant,
        connectionId: String? = null
    ): BundleEditLease {
        val lease = leases.findLockedByBundleIdAndTarget(bundle.id, target)
        if (
            lease == null ||
            !lease.isActive(now) ||
            !lease.isOwnedBy(actor.memberId, actor.sessionId, connectionId) ||
            lease.token != token
        ) {
            fail(ErrorCode.BUNDLE_LEASE_CONFLICT, lease?.let { publicState(it) })
        }
        return lease
    }

    private fun grant(bundle: Bundle, lease: BundleEditLease): LeaseGrant = LeaseGrant(
        bunchId = bundle.bunchId,
        bundleId = bundle.id,
        target = lease.target,
        token = lease.token,
        leaseVersion = lease.version,
        expiresAt = lease.lastHeartbeatAt.plusSeconds(BundleEditLease.LEASE_SECONDS),
        revision = bundle.revision
    )

    private fun publishStates(bundle: Bundle) {
        events.publishEvent(
            BundleLeaseStateChangedEvent(
                bunchId = bundle.bunchId,
                bundleId = bundle.id,
                revision = bundle.revision,
                states = publicStates(bundle.id)
            )
        )
    }

    private fun publishStatesForBundleId(bundleId: Long) {
        val bundle = bundles.findById(bundleId).orElse(null) ?: return
        publishStates(bundle)
    }

    private fun requirePhotoBelongsToBundle(bundleId: Long, target: String) {
        val photoId = target.removePrefix(PHOTO_PREFIX).toLongOrNull()
            .takeIf { target.startsWith(PHOTO_PREFIX) }
            ?: return
        if (photos.findAllByBundleIdOrderByPositionAscIdAsc(bundleId).none { it.id == photoId }) {
            fail(ErrorCode.BUNDLE_PHOTO_CONFLICT)
        }
    }

    private fun normalizeTarget(target: String): String {
        val value = target.trim()
        if (value == TITLE || value == DATE) return value
        if (value.startsWith(PHOTO_PREFIX)) {
            val photoId = value.removePrefix(PHOTO_PREFIX).toLongOrNull()
            if (photoId != null && photoId > 0) return photoTarget(photoId)
        }
        fail(ErrorCode.INVALID_INPUT)
    }

    private fun photoTarget(bundlePhotoId: Long): String {
        if (bundlePhotoId <= 0) fail(ErrorCode.INVALID_INPUT)
        return "$PHOTO_PREFIX$bundlePhotoId"
    }

    private fun validateActor(actor: BundleActor) {
        if (
            actor.memberId <= 0L ||
            actor.sessionId.isBlank() ||
            actor.sessionId.length > 36
        ) {
            fail(ErrorCode.INVALID_INPUT)
        }
    }

    private fun requireConnectionId(connectionId: String) {
        if (connectionId.isBlank() || connectionId.length > 255) fail(ErrorCode.INVALID_INPUT)
    }

    private fun publicState(lease: BundleEditLease): LeaseState = LeaseState(
        target = lease.target,
        memberId = lease.memberId,
        leaseVersion = lease.version,
        expiresAt = lease.lastHeartbeatAt.plusSeconds(BundleEditLease.LEASE_SECONDS)
    )

    private fun fail(code: ErrorCode, detail: Any? = null): Nothing = throw BusinessException(code, detail)

    private fun BundleEditLease.isOwnedBy(actor: BundleActor): Boolean =
        isOwnedBy(actor.memberId, actor.sessionId)

    private companion object {
        const val TITLE = "TITLE"
        const val DATE = "DATE"
        const val PHOTO_PREFIX = "PHOTO:"
    }
}
