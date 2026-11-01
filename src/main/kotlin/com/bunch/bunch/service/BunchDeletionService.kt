package com.bunch.bunch.service

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchActionOperation
import com.bunch.bunch.domain.BunchActionRequest
import com.bunch.bunch.domain.BunchActionRequestRepository
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.MembershipStatus
import com.bunch.bunch.dto.BunchDeletionRequest
import com.bunch.bunch.dto.BunchDepartureResponse
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.service.BundleLeaseService
import com.bunch.bundle.service.BundleRealtimeSessionRegistry
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.image.service.ImageAssetService
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.service.PushJobWriter
import com.bunch.photo.domain.Photo
import com.bunch.photo.service.PhotoService
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class BunchDeletionService(
    private val members: MemberRepository,
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val actionRequests: BunchActionRequestRepository,
    private val accessNotices: BunchAccessNoticeService,
    private val bundles: BundleRepository,
    private val bundlePhotos: BundlePhotoRepository,
    private val leases: BundleLeaseService,
    private val photoService: PhotoService,
    private val imageAssets: ImageAssetService,
    private val pushJobs: PushJobWriter,
    private val realtimeSessions: BundleRealtimeSessionRegistry,
    private val entityManager: EntityManager,
    private val clock: Clock
) {
    @Transactional
    fun delete(
        userId: Long,
        bunchId: Long,
        requestKey: UUID,
        request: BunchDeletionRequest
    ): BunchDepartureResponse {
        validateInput(bunchId, requestKey, request)
        members.findLockedById(userId)?.requireCompleted()
            ?: fail(ErrorCode.USER_NOT_FOUND)
        val now = clock.instant()
        val normalizedConfirmation = request.confirmation.trim()
        val fingerprint = fingerprint(
            request.actorMembershipId,
            request.expectedMembershipVersion,
            normalizedConfirmation
        )

        actionRequests.deleteExpiredByBunchId(bunchId, now)
        actionRequests.flush()
        actionRequests.findActiveByMemberIdAndBunchIdAndOperationAndRequestKey(
            memberId = userId,
            bunchId = bunchId,
            operation = BunchActionOperation.DELETE,
            requestKey = requestKey,
            now = now
        )?.let { previous ->
            if (previous.fingerprint != fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
            return replay(previous, userId)
        }

        val bunch = bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        val actor = requireActor(bunch, userId, request.actorMembershipId)
        if (actor.role != MembershipRole.OWNER) fail(ErrorCode.FORBIDDEN)
        if (bunch.membershipVersion != request.expectedMembershipVersion) {
            fail(
                ErrorCode.BUNCH_MEMBERSHIP_VERSION_CONFLICT,
                mapOf("membershipVersion" to bunch.membershipVersion)
            )
        }

        val bunchName = bunch.name
        val recipients = memberships
            .findAllByBunchIdAndStatus(bunchId, MembershipStatus.ACTIVE)
            .filterNot { it.member.id == userId }
            .map { Recipient(it.member.id, it.id, it) }
        val assets = collectAssetIds(bunch)
        val bundleIds = bundles.findAllByBunchIdOrderByIdAsc(bunchId).map { it.id }
        val photoUploaderMemberIds = uploaderMemberIds(bunchId)
        val savedNotices = recipients.map { recipient ->
            accessNotices.recordDeletion(bunch, recipient.membership, now)
        }

        clearBundleLeases(bundleIds)
        bundleIds.forEach(pushJobs::cancelCommentRemindersForBundle)
        photoUploaderMemberIds.forEach { memberId ->
            photoService.removeAllUploaders(bunchId, memberId)
        }
        requireNoPhotosRemain(bunchId)

        bunch.profileImageAsset = null
        bunches.flush()
        // Managed memberships/photos still point at the soon-to-be-deleted bunch. Let their
        // explicit cleanup flush first, then remove those references from the persistence
        // context before relying on the existing database cascade for the aggregate root.
        entityManager.clear()
        bunches.deleteById(bunchId)
        bunches.flush()
        entityManager.clear()

        recipients.zip(savedNotices).forEach { (recipient, notice) ->
            pushJobs.enqueueDetached(
                recipientMemberIds = listOf(recipient.memberId),
                bunchId = bunchId,
                type = NotificationType.BUNCH_DELETED,
                dedupeKey = "BUNCH_DELETED:$bunchId:${recipient.membershipId}",
                title = "[$bunchName] 번치가 삭제됐어요",
                body = "관리자가 번치를 영구 삭제했어요.",
                target = NotificationTarget(
                    kind = NotificationTargetKind.ACCESS_NOTICE,
                    bunchId = bunchId,
                    noticeId = notice.id
                ),
                now = now
            )
        }

        val result = resultMap(bunchId, actor.id)
        actionRequests.saveAndFlush(
            BunchActionRequest(
                memberId = userId,
                bunchId = bunchId,
                actorMembershipId = actor.id,
                operation = BunchActionOperation.DELETE,
                requestKey = requestKey,
                fingerprint = fingerprint,
                result = result,
                createdAt = now,
                expiresAt = now.plus(ACCESS_NOTICE_RETENTION)
            )
        )
        assets.forEach(imageAssets::releaseAfterCommit)
        registerRealtimeCleanupAfterCommit(bunchId)

        return BunchDepartureResponse(
            bunchId = bunchId,
            departedMembershipId = actor.id,
            outcome = DELETED,
            defaultBunchId = defaultBunchId(userId)
        )
    }

    private fun replay(
        previous: BunchActionRequest,
        userId: Long
    ): BunchDepartureResponse {
        val result = previous.result
        return BunchDepartureResponse(
            bunchId = result.longValue("bunchId") ?: previous.bunchId,
            departedMembershipId = result.longValue("departedMembershipId") ?: previous.actorMembershipId,
            outcome = result["outcome"]?.toString() ?: DELETED,
            defaultBunchId = defaultBunchId(userId)
        )
    }

    private fun requireActor(
        bunch: Bunch,
        userId: Long,
        actorMembershipId: Long
    ): BunchMembership {
        val actor = memberships.findById(actorMembershipId).orElse(null)
        if (
            actor == null ||
            actor.bunch.id != bunch.id ||
            actor.member.id != userId ||
            actor.status != MembershipStatus.ACTIVE
        ) {
            fail(ErrorCode.FORBIDDEN)
        }
        return actor
    }

    private fun collectAssetIds(bunch: Bunch): Set<Long> {
        val sourceAssets = entityManager.createQuery(
            "select photo from Photo photo join fetch photo.imageAsset " +
                "where photo.bunch.id = :bunchId order by photo.id",
            Photo::class.java
        )
            .setParameter("bunchId", bunch.id)
            .resultList
            .map { it.imageAsset.id }

        val bundleAssets = bundles.findAllByBunchIdOrderByIdAsc(bunch.id)
            .flatMap { bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(it.id) }
            .map { it.imageAsset.id }

        return buildSet {
            bunch.profileImageAsset?.id?.let(::add)
            addAll(sourceAssets)
            addAll(bundleAssets)
        }
    }

    private fun uploaderMemberIds(bunchId: Long): List<Long> = entityManager.createQuery(
        "select distinct uploader.member.id from PhotoUploader uploader " +
            "where uploader.photo.bunch.id = :bunchId order by uploader.member.id",
        Long::class.javaObjectType
    )
        .setParameter("bunchId", bunchId)
        .resultList

    private fun clearBundleLeases(bundleIds: List<Long>) {
        bundleIds.forEach { bundleId ->
            if (bundles.findLocked(bundleId) != null) leases.clear(bundleId)
        }
    }

    private fun requireNoPhotosRemain(bunchId: Long) {
        val remaining = entityManager.createQuery(
            "select count(photo) from Photo photo where photo.bunch.id = :bunchId",
            Long::class.java
        )
            .setParameter("bunchId", bunchId)
            .singleResult
        if (remaining != 0L) fail(ErrorCode.INTERNAL_ERROR)
    }

    private fun resultMap(bunchId: Long, membershipId: Long): Map<String, Any?> = mapOf(
        "bunchId" to bunchId,
        "departedMembershipId" to membershipId,
        "outcome" to DELETED
    )

    private fun defaultBunchId(userId: Long): Long? =
        memberships.findFirstByMemberIdAndStatusOrderByLastUsedAtDescIdDesc(userId)?.bunch?.id

    private fun registerRealtimeCleanupAfterCommit(bunchId: Long) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            object : org.springframework.transaction.support.TransactionSynchronization {
                override fun afterCommit() {
                    realtimeSessions.removeForBunch(bunchId)
                }
            }
        )
    }

    private fun validateInput(
        bunchId: Long,
        requestKey: UUID,
        request: BunchDeletionRequest
    ) {
        if (
            bunchId <= 0L ||
            requestKey == UUID(0L, 0L) ||
            request.actorMembershipId <= 0L ||
            request.expectedMembershipVersion < 0L ||
            request.confirmation.trim() != DELETE_CONFIRMATION
        ) {
            fail(ErrorCode.INVALID_INPUT)
        }
    }

    private fun fingerprint(vararg values: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("") { value ->
            val normalized = value.toString()
            "${normalized.length}:$normalized"
        }.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun fail(code: ErrorCode, detail: Any? = null): Nothing =
        throw BusinessException(code, detail)

    private data class Recipient(
        val memberId: Long,
        val membershipId: Long,
        val membership: BunchMembership
    )

    private companion object {
        const val DELETED = "DELETED"
        const val DELETE_CONFIRMATION = "삭제하기"
        val ACCESS_NOTICE_RETENTION: Duration = Duration.ofDays(30)
    }
}

private fun Map<String, Any?>.longValue(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}
