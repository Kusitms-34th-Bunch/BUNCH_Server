package com.bunch.bunch.service

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchAccessNotice
import com.bunch.bunch.domain.BunchAccessNoticeReason
import com.bunch.bunch.domain.BunchAccessNoticeRepository
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.dto.BunchAccessNoticeResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class BunchAccessNoticeService(
    private val notices: BunchAccessNoticeRepository,
    private val memberships: BunchMembershipRepository,
    private val members: MemberRepository,
    private val clock: Clock
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun recordRemoval(
        bunch: Bunch,
        membership: BunchMembership,
        occurredAt: Instant
    ): BunchAccessNotice {
        return notices.findByRecipientMemberIdAndMembershipIdAndReason(
            membership.member.id,
            membership.id,
            BunchAccessNoticeReason.REMOVED
        ) ?: notices.save(
            BunchAccessNotice(
                recipientMemberId = membership.member.id,
                bunchId = bunch.id,
                membershipId = membership.id,
                bunchName = bunch.name,
                characterType = bunch.characterType,
                reason = BunchAccessNoticeReason.REMOVED,
                occurredAt = occurredAt,
                expiresAt = occurredAt.plus(ACCESS_NOTICE_RETENTION)
            )
        )
    }

    @Transactional(readOnly = true)
    fun get(memberId: Long, noticeId: Long): BunchAccessNoticeResponse {
        requireCompleted(memberId)
        val notice = notices.findByIdAndRecipientMemberIdAndExpiresAtAfter(
            noticeId,
            memberId,
            clock.instant()
        )?.takeUnless { hasActiveMembership(memberId, it.bunchId) }
            ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        return BunchAccessNoticeResponse.from(notice)
    }

    @Transactional
    fun delete(memberId: Long, noticeId: Long) {
        requireCompleted(memberId)
        val notice = notices.findById(noticeId).orElse(null) ?: return
        if (notice.recipientMemberId != memberId) fail(ErrorCode.RESOURCE_NOT_FOUND)
        notices.delete(notice)
    }

    @Transactional(readOnly = true)
    fun active(memberId: Long): List<BunchAccessNoticeResponse> {
        requireCompleted(memberId)
        return notices
            .findActiveByRecipientMemberId(memberId, clock.instant())
            .map(BunchAccessNoticeResponse::from)
    }

    @Transactional(readOnly = true)
    fun requireMemberOrAccessNotice(memberId: Long, bunchId: Long) {
        requireCompleted(memberId)
        if (hasActiveMembership(memberId, bunchId)) return

        val notice = notices.findActiveByRecipientMemberId(memberId, clock.instant())
            .firstOrNull { it.bunchId == bunchId }
        if (notice != null) {
            fail(
                ErrorCode.BUNCH_ACCESS_UNAVAILABLE,
                mapOf(
                    "noticeId" to notice.id,
                    "reason" to notice.reason.name
                )
            )
        }
        fail(ErrorCode.FORBIDDEN)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun removeForRejoin(memberId: Long, bunchId: Long): List<Long> {
        val existing = notices.findAllByRecipientMemberIdAndBunchId(memberId, bunchId)
        if (existing.isNotEmpty()) notices.deleteAll(existing)
        return existing.map { it.id }
    }

    private fun requireCompleted(memberId: Long) {
        members.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()
    }

    private fun hasActiveMembership(memberId: Long, bunchId: Long): Boolean =
        memberships.existsByMemberIdAndBunchIdAndStatus(memberId, bunchId)

    private fun fail(errorCode: ErrorCode, detail: Any? = null): Nothing =
        throw BusinessException(errorCode, detail)

    companion object {
        private val ACCESS_NOTICE_RETENTION: Duration = Duration.ofDays(30)
    }
}
