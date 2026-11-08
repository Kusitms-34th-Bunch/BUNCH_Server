package com.bunch.social.service

import com.bunch.bunch.domain.BunchReportPolicy
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bundle.domain.BundleRepository
import com.bunch.feed.domain.FeedContentHideRepository
import com.bunch.feed.domain.FeedContentKind
import com.bunch.feed.dto.FeedBundleInteraction
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.social.domain.SocialCommentRepository
import com.bunch.social.domain.SocialContentReport
import com.bunch.social.domain.SocialContentReportRepository
import com.bunch.social.dto.SocialContentReportRequest
import com.bunch.social.dto.SocialContentReportResult
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class SocialContentReportService(
    private val access: FeedAccessService,
    private val commentService: SocialCommentService,
    private val comments: SocialCommentRepository,
    private val reports: SocialContentReportRepository,
    private val hides: FeedContentHideRepository,
    private val members: MemberRepository,
    private val bunches: BunchRepository,
    private val bundles: BundleRepository,
    private val entityManager: EntityManager,
    private val clock: Clock
) {
    @Transactional
    fun bundle(userId: Long, bundleId: Long, request: SocialContentReportRequest): SocialContentReportResult {
        val detail = BunchReportPolicy.detail(request.reason, request.detail)
        val actorId = lockTargets(userId, bundleId)
        val interaction = access.requireBundleReportTarget(userId, bundleId)
        requireSameActor(actorId, interaction)
        if (interaction.bundle.bunchId == actorId && interaction.actor.role == MembershipRole.OWNER) {
            fail(ErrorCode.FORBIDDEN)
        }
        return accept(userId, actorId, FeedContentKind.BUNDLE, bundleId, request, detail)
    }

    @Transactional
    fun comment(
        userId: Long,
        bundleId: Long,
        commentId: Long,
        request: SocialContentReportRequest
    ): SocialContentReportResult {
        val detail = BunchReportPolicy.detail(request.reason, request.detail)
        val actorId = lockTargets(userId, bundleId, commentId)
        val interaction = access.requireInteraction(userId, bundleId)
        requireSameActor(actorId, interaction)
        // E rejects own/deleted/blocked/global-hidden targets, allowing only the target reporter hide.
        commentService.requireReportable(userId, bundleId, commentId)
        return accept(userId, actorId, FeedContentKind.COMMENT, commentId, request, detail)
    }

    private fun lockTargets(userId: Long, bundleId: Long, commentId: Long? = null): Long {
        positive(bundleId)
        commentId?.let(::positive)
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        val actorId = access.requireActor(userId).bunch.id
        val bundle = bundles.findById(bundleId).orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
        val comment = commentId?.let {
            comments.findById(it).orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
                .also { row -> if (row.bundleId != bundleId) fail(ErrorCode.RESOURCE_NOT_FOUND) }
        }
        setOfNotNull(actorId, bundle.bunchId, comment?.actingBunchId).sorted().forEach {
            bunches.findLocked(it) ?: fail(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
        }
        bundles.findLocked(bundleId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (commentId != null) comments.findLocked(bundleId, commentId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        // Retain the DB locks, discard membership/role/visibility loaded before waiting for them.
        entityManager.clear()
        return actorId
    }

    private fun requireSameActor(actorId: Long, interaction: FeedBundleInteraction) {
        if (interaction.actor.bunch.id != actorId) fail(ErrorCode.CURRENT_BUNCH_REQUIRED)
    }

    private fun accept(
        userId: Long,
        actorId: Long,
        kind: FeedContentKind,
        targetId: Long,
        request: SocialContentReportRequest,
        detail: String?
    ): SocialContentReportResult {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val latest = reports.findFirstByReporterBunchIdAndTargetKindAndTargetIdOrderByWindowStartedAtDescIdDesc(
            actorId,
            kind,
            targetId
        )
        val window = BunchReportPolicy.windowStartedAt(now, latest?.windowStartedAt)
        val count = reports.countByReporterBunchIdAndTargetKindAndTargetIdAndWindowStartedAt(
            actorId,
            kind,
            targetId,
            window
        )
        BunchReportPolicy.requireAvailable(count)
        val saved = reports.saveAndFlush(
            SocialContentReport(
                reporterBunchId = actorId,
                targetKind = kind,
                targetId = targetId,
                reportedByMemberId = userId,
                reason = request.reason,
                detail = detail,
                createdAt = now,
                windowStartedAt = window
            )
        )
        hides.hideForReporter(kind, targetId, actorId)
        return SocialContentReportResult(saved.id)
    }

    private fun positive(id: Long) {
        if (id <= 0) fail(ErrorCode.INVALID_INPUT)
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
