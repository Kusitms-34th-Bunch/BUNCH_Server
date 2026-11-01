package com.bunch.bunch.service

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchActionOperation
import com.bunch.bunch.domain.BunchActionRequest
import com.bunch.bunch.domain.BunchActionRequestRepository
import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.domain.BunchFollowStatus
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.ProfileImageAction
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.dto.BunchProfileRequest
import com.bunch.bunch.dto.BunchProfileResponse
import com.bunch.global.common.ProfileText
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.image.service.ImageAssetService
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.service.NotificationWriter
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class BunchProfileService(
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val members: MemberRepository,
    private val actionRequests: BunchActionRequestRepository,
    private val follows: BunchFollowRepository,
    private val notifications: NotificationRepository,
    private val notificationWriter: NotificationWriter,
    private val images: ProfileImageStorage,
    private val imageAssets: ImageAssetService,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun update(
        memberId: Long,
        bunchId: Long,
        requestKey: UUID,
        request: BunchProfileRequest,
        photo: MultipartFile?
    ): BunchProfileResponse {
        val input = normalize(request, photo)
        preflight(memberId, bunchId, requestKey, input)?.let { return it }

        var uploaded: StoredImage? = null
        var attached = false
        try {
            if (input.imageAction == ProfileImageAction.REPLACE) {
                uploaded = images.upload(requireNotNull(photo))
            }

            val transactionResult = transactions.execute {
                val now = clock.instant()
                val context = lockOwner(memberId, bunchId)
                val previous = actionRequests.findByMemberIdAndBunchIdAndOperationAndRequestKey(
                    memberId,
                    bunchId,
                    BunchActionOperation.PROFILE,
                    requestKey
                )
                if (previous != null) {
                    if (now.isBefore(previous.expiresAt)) {
                        if (previous.fingerprint != input.fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
                        return@execute TransactionResult(responseFrom(previous.result), false)
                    }
                    actionRequests.delete(previous)
                    actionRequests.flush()
                }

                if (context.bunch.profileVersion != input.expectedProfileVersion) {
                    fail(
                        ErrorCode.BUNCH_PROFILE_VERSION_CONFLICT,
                        mapOf("profileVersion" to context.bunch.profileVersion)
                    )
                }
                if (bunches.existsByNameAndIdNot(input.name, bunchId)) fail(ErrorCode.BUNCH_NAME_TAKEN)

                val previousAssetId = context.bunch.profileImageAsset?.id
                val newAsset = when (input.imageAction) {
                    ProfileImageAction.KEEP -> context.bunch.profileImageAsset
                    ProfileImageAction.RESET -> null
                    ProfileImageAction.REPLACE -> imageAssets.store(requireNotNull(uploaded))
                }
                val changed = context.bunch.name != input.name ||
                    context.bunch.introduction != input.introduction ||
                    context.bunch.visibility != input.visibility ||
                    context.bunch.profileImageAsset?.id != newAsset?.id

                if (changed) {
                    context.bunch.name = input.name
                    context.bunch.introduction = input.introduction
                    val oldVisibility = context.bunch.visibility
                    context.bunch.visibility = input.visibility
                    context.bunch.profileImageAsset = newAsset
                    context.bunch.profileVersion++
                    if (oldVisibility != input.visibility) {
                        if (oldVisibility == Visibility.PRIVATE && input.visibility == Visibility.PUBLIC) {
                            approvePending(context.bunch, memberId, now)
                        }
                        writeVisibilityNotification(context.bunch, memberId, input.visibility, now)
                    }
                    imageAssets.releaseAfterCommit(previousAssetId.takeUnless {
                        newAsset?.id == it
                    })
                }

                val response = BunchProfileResponse(context.bunch.id, context.bunch.profileVersion)
                actionRequests.saveAndFlush(
                    BunchActionRequest(
                        memberId = memberId,
                        bunchId = bunchId,
                        actorMembershipId = context.membership.id,
                        operation = BunchActionOperation.PROFILE,
                        requestKey = requestKey,
                        fingerprint = input.fingerprint,
                        result = mapOf(
                            "bunchId" to response.bunchId,
                            "profileVersion" to response.profileVersion
                        ),
                        createdAt = now,
                        expiresAt = now.plus(IDEMPOTENCY_RETENTION)
                    )
                )
                bunches.flush()
                TransactionResult(
                    response = response,
                    attached = input.imageAction == ProfileImageAction.REPLACE
                )
            }!!
            attached = transactionResult.attached
            if (!attached) images.delete(uploaded?.publicId)
            return transactionResult.response
        } catch (ex: DataIntegrityViolationException) {
            images.delete(uploaded?.publicId.takeUnless { attached })
            if (bunches.existsByNameAndIdNot(input.name, bunchId)) {
                fail(ErrorCode.BUNCH_NAME_TAKEN)
            }
            throw ex
        } catch (ex: RuntimeException) {
            if (!attached) images.delete(uploaded?.publicId)
            throw ex
        }
    }

    private fun normalize(request: BunchProfileRequest, photo: MultipartFile?): Input {
        if (request.expectedProfileVersion < 0) fail(ErrorCode.INVALID_INPUT)
        val name = ProfileText.name(request.name)
        val introduction = ProfileText.introduction(request.introduction)
        when (request.imageAction) {
            ProfileImageAction.KEEP, ProfileImageAction.RESET -> if (photo != null) {
                fail(ErrorCode.INVALID_INPUT)
            }
            ProfileImageAction.REPLACE -> if (
                photo == null || photo.isEmpty || photo.size !in 1..MAX_PROFILE_IMAGE_BYTES.toLong()
            ) {
                fail(ErrorCode.INVALID_INPUT)
            }
        }
        val imageHash = if (request.imageAction == ProfileImageAction.REPLACE) {
            digest(readProfileImageBytes(requireNotNull(photo)))
        } else {
            "-"
        }
        val fingerprintInput = listOf(
            name,
            introduction,
            request.visibility.name,
            request.imageAction.name,
            request.expectedProfileVersion.toString(),
            imageHash
        ).joinToString("") { "${it.length}:$it" }
        val fingerprint = digest(fingerprintInput.toByteArray(Charsets.UTF_8))
        return Input(
            name = name,
            introduction = introduction,
            visibility = request.visibility,
            imageAction = request.imageAction,
            expectedProfileVersion = request.expectedProfileVersion,
            fingerprint = fingerprint
        )
    }

    private fun readProfileImageBytes(photo: MultipartFile): ByteArray {
        val bytes = photo.inputStream.use { it.readNBytes(MAX_PROFILE_IMAGE_BYTES + 1) }
        if (bytes.size !in 1..MAX_PROFILE_IMAGE_BYTES) fail(ErrorCode.INVALID_INPUT)
        return bytes
    }

    private fun preflight(
        memberId: Long,
        bunchId: Long,
        requestKey: UUID,
        input: Input
    ): BunchProfileResponse? = transactions.execute {
        val now = clock.instant()
        val context = lockOwner(memberId, bunchId)
        val previous = actionRequests.findByMemberIdAndBunchIdAndOperationAndRequestKey(
            memberId,
            bunchId,
            BunchActionOperation.PROFILE,
            requestKey
        )
        if (previous != null) {
            if (now.isBefore(previous.expiresAt)) {
                if (previous.fingerprint != input.fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
                return@execute responseFrom(previous.result)
            }
            actionRequests.delete(previous)
            actionRequests.flush()
        }
        if (context.bunch.profileVersion != input.expectedProfileVersion) {
            fail(
                ErrorCode.BUNCH_PROFILE_VERSION_CONFLICT,
                mapOf("profileVersion" to context.bunch.profileVersion)
            )
        }
        null
    }

    private fun lockOwner(memberId: Long, bunchId: Long): Locked {
        members.findLockedById(memberId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        val bunch = bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(memberId, bunchId)
            ?: fail(ErrorCode.FORBIDDEN)
        if (membership.role != MembershipRole.OWNER) fail(ErrorCode.FORBIDDEN)
        return Locked(bunch, membership)
    }

    private fun approvePending(bunch: Bunch, actorMemberId: Long, now: Instant) {
        val pending = follows.findAllByFollowedBunchIdAndStatus(
            bunch.id,
            BunchFollowStatus.PENDING,
            Pageable.unpaged()
        ).content.sortedBy { it.id }
        if (pending.isEmpty()) return

        val followerBunches = follows.findCardBunches(pending.map { it.followerBunchId })
            .associateBy { it.id }
        pending.forEach { follow ->
            follow.approve(actorMemberId, now)
            notificationWriter.followApproved(
                follower = followerBunches[follow.followerBunchId] ?: fail(ErrorCode.INTERNAL_ERROR),
                target = bunch,
                follow = follow,
                actorId = actorMemberId
            )
        }
    }

    private fun writeVisibilityNotification(
        bunch: Bunch,
        actorMemberId: Long,
        visibility: Visibility,
        now: Instant
    ) {
        val public = visibility == Visibility.PUBLIC
        memberships.findActiveMembers(bunch.id)
            .filter { it.member.id != actorMemberId }
            .forEach { recipient ->
                notifications.save(
                    Notification(
                        recipientMemberId = recipient.member.id,
                        bunchId = bunch.id,
                        type = NotificationType.BUNCH_VISIBILITY_CHANGED,
                        dedupeKey = "BUNCH_VISIBILITY_CHANGED:${bunch.id}:${bunch.profileVersion}",
                        title = if (public) {
                            "우리 번치가 공개로 바뀌었어요"
                        } else {
                            "우리 번치가 비공개로 바뀌었어요"
                        },
                        body = if (public) {
                            "이제 누구나 우리 번치와 번들을 볼 수 있어요."
                        } else {
                            "이제 팔로우를 승인한 번치만 우리 번치와 번들을 볼 수 있어요."
                        },
                        target = NotificationTarget(NotificationTargetKind.BUNCH_HOME, bunch.id),
                        createdAt = now
                    )
                )
            }
    }

    private fun responseFrom(result: Map<String, Any?>) = BunchProfileResponse(
        bunchId = (result["bunchId"] as? Number)?.toLong() ?: fail(ErrorCode.INTERNAL_ERROR),
        profileVersion = (result["profileVersion"] as? Number)?.toLong() ?: fail(ErrorCode.INTERNAL_ERROR)
    )

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun fail(code: ErrorCode, detail: Any? = null): Nothing = throw BusinessException(code, detail)

    private data class Input(
        val name: String,
        val introduction: String,
        val visibility: Visibility,
        val imageAction: ProfileImageAction,
        val expectedProfileVersion: Long,
        val fingerprint: String
    )

    private data class TransactionResult(
        val response: BunchProfileResponse,
        val attached: Boolean
    )

    private data class Locked(val bunch: Bunch, val membership: BunchMembership)

    companion object {
        private const val MAX_PROFILE_IMAGE_BYTES = 5 * 1024 * 1024
        private val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(30)
    }
}
