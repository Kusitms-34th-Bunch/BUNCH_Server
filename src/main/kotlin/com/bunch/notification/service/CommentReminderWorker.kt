package com.bunch.notification.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleCommentRepository
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.MemberRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class CommentReminderWorker(
    private val bunches: BunchRepository,
    private val bundles: BundleRepository,
    private val participants: BundleParticipantRepository,
    private val comments: BundleCommentRepository,
    private val members: MemberRepository,
    private val memberships: BunchMembershipRepository,
    private val writer: NotificationWriter,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelayString = "\${notification.reminder.poll-interval-ms:60000}")
    fun poll() {
        try {
            runOnce()
        } catch (ex: Exception) {
            logger.warn("Comment reminder polling failed: {}", ex.javaClass.simpleName)
        }
    }

    fun runOnce(now: Instant = clock.instant()): Int {
        val candidates = participants.findDueReminderCandidates(
            due24At = now.minus(REMINDER_24),
            due72At = now.minus(REMINDER_72)
        )
        return candidates
            .groupBy { it.bundleId }
            .entries
            .sumOf { (bundleId, bundleCandidates) ->
                try {
                    transactions.execute {
                        processBundle(
                            bundleId = bundleId,
                            participantIds = bundleCandidates.map { it.participantId }.toSet(),
                            now = now
                        )
                    } ?: 0
                } catch (ex: Exception) {
                    logger.warn(
                        "Comment reminder bundle failed: id={}, error={}",
                        bundleId,
                        ex.javaClass.simpleName
                    )
                    0
                }
            }
    }

    private fun processBundle(bundleId: Long, participantIds: Set<Long>, now: Instant): Int {
        val bunchId = bundles.findBunchId(bundleId) ?: return 0
        if (bunches.findLocked(bunchId) == null) return 0
        val bundle = bundles.findLocked(bundleId) ?: return 0
        if (bundle.status != BundleStatus.DRAFT) return 0

        var sent = 0
        participantIds.forEach { participantId ->
            val participant = participants.findById(participantId).orElse(null) ?: return@forEach
            if (!eligible(participant, bundle)) return@forEach
            dueHours(participant, now).forEach { hours ->
                writer.commentReminder(bundle, participant, hours, now)
                if (hours == HOURS_24) participant.reminder24SentAt = now
                else participant.reminder72SentAt = now
                sent++
            }
        }
        return sent
    }

    private fun eligible(participant: BundleParticipant, bundle: Bundle): Boolean {
        if (participant.remindersCancelledAt != null) return false
        if (!members.findById(participant.memberId).map { it.accountStatus == AccountStatus.ACTIVE }.orElse(false)) {
            return false
        }
        if (memberships.findByMemberIdAndBunchIdAndStatus(participant.memberId, bundle.bunchId) == null) {
            return false
        }
        return comments.findByBundleIdAndMemberId(bundle.id, participant.memberId)?.content?.isBlank() != false
    }

    private fun dueHours(participant: BundleParticipant, now: Instant): List<Int> = buildList {
        if (participant.reminder24SentAt == null && !participant.joinedAt.plus(REMINDER_24).isAfter(now)) {
            add(HOURS_24)
        }
        if (participant.reminder72SentAt == null && !participant.joinedAt.plus(REMINDER_72).isAfter(now)) {
            add(HOURS_72)
        }
    }

    companion object {
        private val REMINDER_24: Duration = Duration.ofHours(24)
        private val REMINDER_72: Duration = Duration.ofHours(72)
        private const val HOURS_24 = 24
        private const val HOURS_72 = 72
    }
}
