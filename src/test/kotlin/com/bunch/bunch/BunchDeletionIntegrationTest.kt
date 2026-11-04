package com.bunch.bunch

import com.bunch.bunch.domain.BunchActionOperation
import com.bunch.bunch.domain.BunchActionRequest
import com.bunch.bunch.domain.BunchActionRequestRepository
import com.bunch.bunch.domain.BunchBlock
import com.bunch.bunch.domain.BunchBlockRepository
import com.bunch.bunch.domain.BunchFollow
import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.domain.BunchFollowStatus
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchProfileReport
import com.bunch.bunch.domain.BunchProfileReportRepository
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipStatus
import com.bunch.bunch.dto.BunchDeletionRequest
import com.bunch.bunch.service.BunchDeletionService
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleComment
import com.bunch.bundle.domain.BundleCommentRepository
import com.bunch.bundle.domain.BundleEditLease
import com.bunch.bundle.domain.BundleEditLeaseRepository
import com.bunch.bundle.domain.BundlePhoto
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.service.BundleAccessService
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.service.BundleRealtimeSessionRegistry
import com.bunch.bundle.service.BundleRealtimeSubscription
import com.bunch.bunch.domain.Visibility
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.push.PushAuthorizationStatus
import com.bunch.notification.domain.push.PushDevice
import com.bunch.notification.domain.push.PushDeviceEnvironment
import com.bunch.notification.domain.push.PushDeviceRepository
import com.bunch.notification.domain.push.PushJobRepository
import com.bunch.notification.domain.push.PushJob
import com.bunch.notification.domain.push.PushJobStatus
import com.bunch.notification.dto.PushAlert
import com.bunch.notification.dto.PushAps
import com.bunch.notification.dto.PushPayload
import com.bunch.photo.domain.DateSource
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import com.bunch.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BunchDeletionIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var deletions: BunchDeletionService

    @Autowired
    private lateinit var bundleAccess: BundleAccessService

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Autowired
    private lateinit var actionRequests: BunchActionRequestRepository

    @Autowired
    private lateinit var accessNotices: com.bunch.bunch.domain.BunchAccessNoticeRepository

    @Autowired
    private lateinit var bundleRepository: BundleRepository

    @Autowired
    private lateinit var bundlePhotos: BundlePhotoRepository

    @Autowired
    private lateinit var bundleParticipants: BundleParticipantRepository

    @Autowired
    private lateinit var bundleComments: BundleCommentRepository

    @Autowired
    private lateinit var leases: BundleEditLeaseRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var uploaders: PhotoUploaderRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var follows: BunchFollowRepository

    @Autowired
    private lateinit var blocks: BunchBlockRepository

    @Autowired
    private lateinit var reports: BunchProfileReportRepository

    @Autowired
    private lateinit var pushDevices: PushDeviceRepository

    @Autowired
    private lateinit var pushJobs: PushJobRepository

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var realtimeSessions: BundleRealtimeSessionRegistry

    @AfterEach
    fun cleanNoticeFailureTrigger() {
        realtimeSessions.remove("issue-50-deleted-bunch")
        jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_notification ON bunch_access_notices")
        jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
    }

    @Test
    fun `deletion removes bunch graph and preserves notice action and device push snapshots`() {
        val owner = member()
        val recipient = member()
        val otherOwner = member()
        val fallbackBunch = create(owner)
        val bunch = create(owner)
        join(recipient, issue(owner, bunch.id))
        val otherBunch = create(otherOwner)
        val ownerMembership = activeMembership(owner, bunch.id)
        val bunchEntity = bunchRepository.findById(bunch.id).orElseThrow()
        val now = Instant.now().minusSeconds(1)

        assertThat(countWhere("SELECT count(*) FROM bunch_memberships WHERE member_id = ? AND status = 'ACTIVE'", owner))
            .isEqualTo(2)

        val profileAsset = asset("profiles/deletion", "https://images.example/profile.jpg")
        bunchEntity.profileImageAsset = profileAsset
        bunchRepository.saveAndFlush(bunchEntity)
        val sourceAsset = asset("photos/deletion", "https://images.example/photo.jpg")
        val publishedAsset = asset("bundles/deletion", "https://images.example/published.jpg")
        val sharedAsset = asset("bundles/shared", "https://images.example/shared.jpg")
        val photo = photos.saveAndFlush(
            Photo(
                bunch = bunchEntity,
                imageAsset = sourceAsset,
                fileHash = "a".repeat(64),
                processingStatus = ProcessingStatus.READY,
                dateSource = DateSource.UNKNOWN
            )
        )
        uploaders.saveAndFlush(
            PhotoUploader(
                photo = photo,
                member = members.findById(owner).orElseThrow(),
                uploadBatchId = UUID.randomUUID()
            )
        )
        val bundle = bundleRepository.saveAndFlush(
            Bundle(
                bunchId = bunch.id,
                createdBy = owner,
                status = BundleStatus.PUBLISHED,
                title = "발행본",
                visibility = Visibility.PUBLIC,
                createdAt = now,
                updatedAt = now,
                publishedAt = now
            )
        )
        bundlePhotos.saveAndFlush(
            BundlePhoto(
                bundleId = bundle.id,
                photoId = photo.id,
                imageAsset = publishedAsset,
                position = 0,
                addedBy = owner,
                addedAt = now
            )
        )
        bundlePhotos.saveAndFlush(
            BundlePhoto(
                bundleId = bundle.id,
                photoId = null,
                imageAsset = sharedAsset,
                position = 1,
                addedBy = owner,
                addedAt = now
            )
        )
        bundleParticipants.saveAndFlush(
            BundleParticipant(bundleId = bundle.id, memberId = owner, joinedAt = now)
        )
        bundleComments.saveAndFlush(
            BundleComment(
                bundleId = bundle.id,
                memberId = owner,
                content = "기록",
                createdAt = now,
                updatedAt = now
            )
        )
        leases.saveAndFlush(
            BundleEditLease(
                bundleId = bundle.id,
                target = "TITLE",
                memberId = owner,
                sessionId = UUID.randomUUID().toString(),
                connectionId = "issue-50-connection",
                token = UUID.randomUUID(),
                version = 1,
                lastHeartbeatAt = now
            )
        )
        val otherBundle = bundleRepository.saveAndFlush(
            Bundle(
                bunchId = otherBunch.id,
                createdBy = otherOwner,
                status = BundleStatus.PUBLISHED,
                title = "공유 자산 보존본",
                visibility = Visibility.PUBLIC,
                createdAt = now,
                updatedAt = now,
                publishedAt = now
            )
        )
        bundlePhotos.saveAndFlush(
            BundlePhoto(
                bundleId = otherBundle.id,
                photoId = null,
                imageAsset = sharedAsset,
                position = 0,
                addedBy = otherOwner,
                addedAt = now
            )
        )
        notifications.saveAndFlush(
            Notification(
                recipientMemberId = recipient,
                bunchId = bunch.id,
                type = NotificationType.MEMBER_JOINED,
                dedupeKey = "issue-50-existing-notification",
                title = "기존 알림",
                body = "삭제 전에 남아 있던 알림",
                target = com.bunch.notification.dto.NotificationTarget(
                    kind = com.bunch.notification.domain.NotificationTargetKind.MEMBERS,
                    bunchId = bunch.id
                ),
                createdAt = now
            )
        )

        follows.saveAndFlush(
            BunchFollow(
                followerBunchId = bunch.id,
                followedBunchId = otherBunch.id,
                status = BunchFollowStatus.APPROVED,
                createdByMemberId = owner,
                approvedByMemberId = otherOwner,
                createdAt = now,
                approvedAt = now
            )
        )
        blocks.saveAndFlush(
            BunchBlock(
                blockerBunchId = otherBunch.id,
                blockedBunchId = bunch.id,
                createdByMemberId = otherOwner,
                createdAt = now
            )
        )
        reports.saveAndFlush(
            BunchProfileReport(
                reporterBunchId = otherBunch.id,
                targetBunchId = bunch.id,
                reportedByMemberId = otherOwner,
                reason = BunchReportReason.SPAM,
                detail = "중복",
                createdAt = now
            )
        )
        val previousAction = actionRequests.saveAndFlush(
            BunchActionRequest(
                memberId = owner,
                bunchId = bunch.id,
                actorMembershipId = ownerMembership.id,
                operation = BunchActionOperation.LEAVE,
                requestKey = UUID.randomUUID(),
                fingerprint = "b".repeat(64),
                result = mapOf("outcome" to "LEFT"),
                createdAt = now,
                expiresAt = now.plusSeconds(3600)
            )
        )
        val pushDevice = pushDevices.saveAndFlush(
            PushDevice(
                installationId = UUID.randomUUID(),
                memberId = recipient,
                sessionId = UUID.randomUUID(),
                environment = PushDeviceEnvironment.SANDBOX,
                topic = "com.bunch.test",
                tokenCiphertext = byteArrayOf(1, 2, 3),
                tokenHash = "c".repeat(64),
                authorizationStatus = PushAuthorizationStatus.AUTHORIZED,
                registeredAt = now
            )
        )
        val reminderJob = pushJobs.saveAndFlush(
            PushJob(
                id = UUID.randomUUID(),
                deviceId = pushDevice.id,
                recipientMemberId = recipient,
                sessionId = pushDevice.sessionId,
                bunchId = bunch.id,
                notificationId = null,
                dedupeKey = "issue-50-comment-reminder",
                type = NotificationType.COMMENT_REMINDER,
                payload = PushPayload(
                    aps = PushAps(PushAlert("독촉", "코멘트를 남겨 주세요")),
                    eventId = UUID.randomUUID(),
                    type = NotificationType.COMMENT_REMINDER,
                    target = mapOf("kind" to "BUNDLE", "bunchId" to bunch.id, "bundleId" to bundle.id)
                ),
                availableAt = now,
                expiresAt = now.plusSeconds(86400),
                createdAt = now
            )
        )

        val key = UUID.randomUUID()
        val connectionId = "issue-50-deleted-bunch"
        assertThat(realtimeSessions.bind(connectionId, BundleActor(owner, UUID.randomUUID().toString()))).isTrue()
        assertThat(
            realtimeSessions.addSubscription(
                connectionId,
                BundleRealtimeSubscription(
                    id = "deleted-bunch-topic",
                    destination = "/topic/bunches/${bunch.id}/bundles/${bundle.id}",
                    bunchId = bunch.id,
                    bundleId = bundle.id
                )
            )
        ).isTrue()
        val result = deletions.delete(
            owner,
            bunch.id,
            key,
            BunchDeletionRequest(ownerMembership.id, bunchEntity.membershipVersion, "  삭제하기  ")
        )

        assertThat(result.bunchId).isEqualTo(bunch.id)
        assertThat(result.departedMembershipId).isEqualTo(ownerMembership.id)
        assertThat(result.outcome).isEqualTo("DELETED")
        assertThat(result.defaultBunchId).isEqualTo(fallbackBunch.id)
        assertThat(realtimeSessions.identity(connectionId)).isNull()
        assertThat(bunchRepository.findById(bunch.id)).isEmpty
        assertError(ErrorCode.FORBIDDEN) { bundleAccess.read(owner, bunch.id, bundle.id) }
        assertError(ErrorCode.BUNCH_ACCESS_UNAVAILABLE) { bundleAccess.read(recipient, bunch.id, bundle.id) }
        assertThat(countWhere("SELECT count(*) FROM bunch_memberships WHERE bunch_id = ?", bunch.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM bunch_invitations WHERE bunch_id = ?", bunch.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM photos WHERE bunch_id = ?", bunch.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM photo_uploaders WHERE photo_id NOT IN (SELECT id FROM photos)")).isZero
        assertThat(countWhere("SELECT count(*) FROM bundles WHERE bunch_id = ?", bunch.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM bundle_requests WHERE bunch_id = ?", bunch.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM bundle_photos WHERE bundle_id = ?", bundle.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM bundle_participants WHERE bundle_id = ?", bundle.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM bundle_comments WHERE bundle_id = ?", bundle.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM bundle_edit_leases WHERE bundle_id = ?", bundle.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM bunch_follows WHERE follower_bunch_id = ? OR followed_bunch_id = ?", bunch.id, bunch.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM bunch_blocks WHERE blocker_bunch_id = ? OR blocked_bunch_id = ?", bunch.id, bunch.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM bunch_profile_reports WHERE reporter_bunch_id = ? OR target_bunch_id = ?", bunch.id, bunch.id)).isZero
        assertThat(bunchRepository.findById(otherBunch.id)).isPresent
        assertThat(bundleRepository.findById(otherBundle.id)).isPresent
        assertThat(accessNotices.findAllByRecipientMemberIdAndBunchId(recipient, bunch.id)).hasSize(1)
        assertThat(accessNotices.findAllByRecipientMemberIdAndBunchId(recipient, bunch.id).single().reason)
            .isEqualTo(com.bunch.bunch.domain.BunchAccessNoticeReason.DELETED)
        assertThat(accessNotices.findAllByRecipientMemberIdAndBunchId(owner, bunch.id)).isEmpty()
        assertThat(actionRequests.findById(previousAction.id)).isPresent
        val savedAction = actionRequests.findByMemberIdAndBunchIdAndOperationAndRequestKey(
            owner,
            bunch.id,
            BunchActionOperation.DELETE,
            key
        )
        assertThat(savedAction).isNotNull
        assertThat(countWhere("SELECT count(*) FROM notifications WHERE type = 'BUNCH_DELETED' AND bunch_id = ?", bunch.id)).isZero
        assertThat(countWhere("SELECT count(*) FROM notifications WHERE bunch_id = ?", bunch.id)).isZero
        assertThat(pushJobs.findById(reminderJob.id).orElseThrow().status).isEqualTo(PushJobStatus.CANCELLED)
        val jobs = pushJobs.findAll().filter { it.type == NotificationType.BUNCH_DELETED }
        assertThat(jobs).hasSize(1)
        assertThat(jobs.single().recipientMemberId).isEqualTo(recipient)
        val noticeId = accessNotices.findAllByRecipientMemberIdAndBunchId(recipient, bunch.id).single().id
        assertThat((jobs.single().payload.target["noticeId"] as Number).toLong()).isEqualTo(noticeId)
        assertThat(assets.existsById(profileAsset.id)).isFalse
        assertThat(assets.existsById(sourceAsset.id)).isFalse
        assertThat(assets.existsById(publishedAsset.id)).isFalse
        assertThat(assets.existsById(sharedAsset.id)).isTrue
    }

    @Test
    fun `invalid owner or notice write failure leaves the whole deletion unchanged`() {
        val owner = member()
        val recipient = member()
        val bunch = create(owner)
        join(recipient, issue(owner, bunch.id))
        val ownerMembership = activeMembership(owner, bunch.id)
        val recipientMembership = activeMembership(recipient, bunch.id)
        val version = bunchRepository.findById(bunch.id).orElseThrow().membershipVersion

        assertError(ErrorCode.INVALID_INPUT) {
            deletions.delete(
                owner,
                bunch.id,
                UUID.randomUUID(),
                BunchDeletionRequest(ownerMembership.id, version, "삭제 하")
            )
        }
        assertError(ErrorCode.FORBIDDEN) {
            deletions.delete(
                recipient,
                bunch.id,
                UUID.randomUUID(),
                BunchDeletionRequest(recipientMembership.id, version, "삭제하기")
            )
        }
        assertThat(bunchRepository.findById(bunch.id)).isPresent

        rejectWrite("bunch_test_reject_notification", "bunch_access_notices", "INSERT")
        assertThatThrownBy {
            deletions.delete(
                owner,
                bunch.id,
                UUID.randomUUID(),
                BunchDeletionRequest(ownerMembership.id, version, "삭제하기")
            )
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(bunchRepository.findById(bunch.id)).isPresent
        assertThat(memberships.findById(ownerMembership.id).orElseThrow().status)
            .isEqualTo(MembershipStatus.ACTIVE)
        assertThat(accessNotices.findAllByRecipientMemberIdAndBunchId(recipient, bunch.id)).isEmpty()
        assertThat(countWhere("SELECT count(*) FROM bunch_action_requests WHERE bunch_id = ?", bunch.id)).isZero
    }

    @Test
    fun `same deletion request serializes and replay computes the current default`() {
        val owner = member()
        val recipient = member()
        val fallback = create(owner)
        val bunch = create(owner)
        join(recipient, issue(owner, bunch.id))
        val ownerMembership = activeMembership(owner, bunch.id)
        val request = BunchDeletionRequest(
            ownerMembership.id,
            bunchRepository.findById(bunch.id).orElseThrow().membershipVersion,
            "삭제하기"
        )
        val key = UUID.randomUUID()

        val results = concurrently(
            listOf(
                { deletions.delete(owner, bunch.id, key, request) },
                { deletions.delete(owner, bunch.id, key, request) }
            )
        )

        assertThat(results).allMatch { it.isSuccess }
        assertThat(results.map { it.getOrThrow() }.map { it.outcome }).containsOnly("DELETED")
        assertThat(results.map { it.getOrThrow() }.map { it.defaultBunchId }).containsOnly(fallback.id)
        assertThat(countWhere("SELECT count(*) FROM bunch_action_requests WHERE bunch_id = ? AND operation = 'DELETE'", bunch.id)).isEqualTo(1)
        assertThat(countWhere("SELECT count(*) FROM bunch_access_notices WHERE bunch_id = ?", bunch.id)).isEqualTo(1)
    }

    @Test
    fun `expired request key can execute once and an active key fingerprint cannot change`() {
        val owner = member()
        val fallback = create(owner)
        val bunch = create(owner)
        val ownerMembership = activeMembership(owner, bunch.id)
        val version = bunchRepository.findById(bunch.id).orElseThrow().membershipVersion
        val key = UUID.randomUUID()
        val expiredAt = Instant.now().minusSeconds(1)
        actionRequests.saveAndFlush(
            BunchActionRequest(
                memberId = owner,
                bunchId = bunch.id,
                actorMembershipId = ownerMembership.id,
                operation = BunchActionOperation.DELETE,
                requestKey = key,
                fingerprint = "e".repeat(64),
                result = mapOf("outcome" to "DELETED"),
                createdAt = expiredAt.minusSeconds(30 * 24 * 60 * 60L),
                expiresAt = expiredAt
            )
        )

        val result = deletions.delete(
            owner,
            bunch.id,
            key,
            BunchDeletionRequest(ownerMembership.id, version, "삭제하기")
        )

        assertThat(result.defaultBunchId).isEqualTo(fallback.id)
        assertThat(actionRequests.findByMemberIdAndBunchIdAndOperationAndRequestKey(
            owner,
            bunch.id,
            BunchActionOperation.DELETE,
            key
        )).isNotNull
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) {
            deletions.delete(
                owner,
                bunch.id,
                key,
                BunchDeletionRequest(ownerMembership.id, version + 1, "삭제하기")
            )
        }
    }

    private fun activeMembership(memberId: Long, bunchId: Long) =
        memberships.findByMemberIdAndBunchIdAndStatus(memberId, bunchId, MembershipStatus.ACTIVE)!!

    private fun asset(prefix: String, url: String) = assets.saveAndFlush(
        ImageAsset(publicId = "$prefix/${UUID.randomUUID()}", url = url)
    )

    private fun countWhere(sql: String, vararg args: Any): Long =
        jdbc.queryForObject(sql, Long::class.java, *args)!!
}
