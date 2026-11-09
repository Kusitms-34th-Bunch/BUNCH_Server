package com.bunch.notification.service

import com.bunch.bunch.domain.BunchBlockRepository
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.BundleCommentRepository
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.push.PushDeviceRepository
import com.bunch.notification.domain.push.PushJob
import com.bunch.notification.domain.push.PushJobRepository
import com.bunch.notification.domain.push.PushJobStatus
import com.bunch.notification.domain.push.PushPreferenceRepository
import com.bunch.notification.dto.ApnsResponse
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.dto.PushAttempt
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class PushWorker(
    private val jobs: PushJobRepository,
    private val devices: PushDeviceRepository,
    private val preferences: PushPreferenceRepository,
    private val members: MemberRepository,
    private val memberships: BunchMembershipRepository,
    private val bunches: BunchRepository,
    private val blocks: BunchBlockRepository,
    private val bundles: BundleRepository,
    private val participants: BundleParticipantRepository,
    private val comments: BundleCommentRepository,
    private val sessions: RefreshTokenRepository,
    private val cipher: DeviceTokenCipher,
    private val apns: ApnsClient,
    private val clock: Clock,
    private val publishedAccess: PublishedNotificationAccess,
    transactionManager: PlatformTransactionManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelayString = "\${notification.push.poll-interval-ms:15000}")
    fun poll() {
        try {
            runOnce()
        } catch (ex: RuntimeException) {
            logger.warn("Push polling failed: {}", ex.javaClass.simpleName)
        }
    }

    @Synchronized
    fun runOnce(): Int {
        if (!apns.configured()) return 0
        var attempted = 0
        repeat(50) {
            var found = false
            val snapshot = transactions.execute {
                val now = clock.instant()
                val job = jobs.findFirstByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAscCreatedAtAscIdAsc(
                    PushJobStatus.PENDING,
                    now
                ) ?: return@execute null
                found = true
                reserve(job, now)
            }
            if (!found) {
                transactions.executeWithoutResult { jobs.deleteFinishedBefore(clock.instant().minus(Duration.ofDays(30))) }
                return attempted
            }
            if (snapshot != null) {
                // The attempt and next retry time are committed before external HTTP.
                val result = try {
                    apns.send(
                        snapshot.environment, snapshot.topic, cipher.decrypt(snapshot.ciphertext),
                        snapshot.jobId, snapshot.payload, snapshot.expiresAt
                    )
                } catch (_: RuntimeException) {
                    ApnsResponse(400, "ProviderConfigurationFailure")
                }
                transactions.executeWithoutResult { finish(snapshot, result, clock.instant()) }
                attempted++
            }
        }
        return attempted
    }

    private fun reserve(job: PushJob, now: Instant): PushAttempt? {
        if (now >= job.expiresAt || job.attempts >= MAX_ATTEMPTS) {
            job.status = PushJobStatus.FAILED
            job.lastErrorCode = if (now >= job.expiresAt) "Expired" else "AttemptsExhausted"
            return null
        }
        val device = job.deviceId?.let { devices.findById(it).orElse(null) }
        if (device == null || device.memberId != job.recipientMemberId || device.sessionId != job.sessionId ||
            !device.authorizationStatus.canReceive
        ) {
            cancel(job, "DeviceUnavailable")
            return null
        }
        val preference = preferences.findById(job.recipientMemberId).orElse(null)
        if (preference?.enabled == false || preference?.types?.get(job.type.name) == false || !eligible(job)) {
            cancel(job, "RecipientUnavailable")
            return null
        }
        val sessionActive = try {
            sessions.isActive(job.sessionId.toString(), job.recipientMemberId)
        } catch (_: RuntimeException) {
            job.availableAt = now.plus(RETRY_DELAY)
            job.lastErrorCode = "SessionStoreUnavailable"
            return null
        }
        if (!sessionActive) {
            cancel(job, "SessionEnded")
            return null
        }
        job.attempts++
        job.availableAt = now.plus(RETRY_DELAY)
        return PushAttempt(
            job.id, job.attempts, device.id, device.memberId, device.tokenHash, device.registeredAt,
            device.environment, device.topic, device.tokenCiphertext.copyOf(), job.payload, job.expiresAt
        )
    }

    private fun eligible(job: PushJob): Boolean {
        val member = members.findById(job.recipientMemberId).orElse(null) ?: return false
        if (member.accountStatus != AccountStatus.ACTIVE || member.signupStatus != SignupStatus.COMPLETED) return false
        if (job.type == NotificationType.MEMBER_REMOVED || job.type == NotificationType.BUNCH_DELETED) return true
        if (memberships.findByMemberIdAndBunchIdAndStatus(job.recipientMemberId, job.bunchId) == null) return false
        val target = job.payload.target
        val kind = target["kind"]?.toString() ?: return false
        return when (kind) {
            NotificationTargetKind.DRAFT.name -> {
                val bundleId = (target["bundleId"] as? Number)?.toLong() ?: return false
                val bundle = bundles.findById(bundleId).orElse(null) ?: return false
                val participant = participants.findByBundleIdAndMemberId(bundleId, job.recipientMemberId)
                    ?: return false
                bundle.bunchId == job.bunchId && bundle.status == BundleStatus.DRAFT &&
                    (job.type != NotificationType.COMMENT_REMINDER ||
                        (participant.remindersCancelledAt == null &&
                            comments.findByBundleIdAndMemberId(bundleId, job.recipientMemberId)?.content?.isBlank() != false))
            }
            NotificationTargetKind.BUNDLE.name, NotificationTargetKind.BUNDLE_COMMENTS.name -> {
                val bundleId = (target["bundleId"] as? Number)?.toLong() ?: return false
                publishedAccess.canDeliver(
                    job.bunchId,
                    NotificationTarget(
                        kind = NotificationTargetKind.valueOf(kind),
                        bunchId = job.bunchId,
                        bundleId = bundleId,
                        targetBunchId = (target["targetBunchId"] as? Number)?.toLong(),
                        sourceBunchId = (target["sourceBunchId"] as? Number)?.toLong(),
                        commentId = (target["commentId"] as? Number)?.toLong()
                    )
                )
            }
            NotificationTargetKind.OTHER_BUNCH_PROFILE.name -> {
                val targetId = (target["targetBunchId"] as? Number)?.toLong() ?: return false
                bunches.existsById(targetId) && !blocks.blocked(job.bunchId, targetId)
            }
            NotificationTargetKind.BUNCH_HOME.name, NotificationTargetKind.MEMBERS.name,
            NotificationTargetKind.ROLE_PERMISSIONS.name, NotificationTargetKind.DRAFT_LIST.name -> true
            else -> false
        }
    }

    private fun finish(attempt: PushAttempt, response: ApnsResponse, now: Instant) {
        val invalidatesDevice = response.reason in setOf("Unregistered", "BadDeviceToken", "DeviceTokenNotForTopic")
        // Registration also locks device before cancelling its jobs.
        val current = if (invalidatesDevice) devices.findLocked(attempt.deviceId) else null
        val job = jobs.findLocked(attempt.jobId) ?: return
        if (job.status != PushJobStatus.PENDING || job.attempts != attempt.attempt) return
        job.lastErrorCode = response.reason
        if (response.status == 200) {
            job.status = PushJobStatus.SENT
            job.sentAt = now
            return
        }
        if (invalidatesDevice) {
            if (current?.memberId == attempt.memberId && current.tokenHash == attempt.tokenHash &&
                current.registeredAt == attempt.registeredAt
            ) {
                jobs.cancelPendingForDevice(attempt.memberId, attempt.deviceId, "DeviceInvalidated")
                devices.deleteIfCurrent(attempt.deviceId, attempt.memberId, attempt.tokenHash, attempt.registeredAt)
                job.deviceId = null
            }
            job.status = PushJobStatus.FAILED
            return
        }
        val transient = response.status == 429 || response.status >= 500 || response.reason == "ExpiredProviderToken"
        val next = maxOf(now.plus(RETRY_DELAY), response.retryAfter ?: now)
        if (transient && job.attempts < MAX_ATTEMPTS && next < job.expiresAt) {
            job.availableAt = next
        } else {
            job.status = PushJobStatus.FAILED
        }
    }

    private fun cancel(job: PushJob, reason: String) {
        job.status = PushJobStatus.CANCELLED
        job.lastErrorCode = reason
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        val RETRY_DELAY: Duration = Duration.ofMinutes(15)
    }
}
