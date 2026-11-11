package com.bunch.bunch.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface BunchAccessNoticeRepository : JpaRepository<BunchAccessNotice, Long> {
    fun findByIdAndRecipientMemberIdAndExpiresAtAfter(
        id: Long,
        recipientMemberId: Long,
        now: Instant
    ): BunchAccessNotice?

    fun findByRecipientMemberIdAndMembershipIdAndReason(
        recipientMemberId: Long,
        membershipId: Long,
        reason: BunchAccessNoticeReason
    ): BunchAccessNotice?

    @Query("""select notice from BunchAccessNotice notice
        where notice.recipientMemberId = :memberId and notice.expiresAt > :now
        order by notice.occurredAt desc, notice.id desc""")
    fun findActiveByRecipientMemberId(
        memberId: Long,
        now: Instant
    ): List<BunchAccessNotice>

    // Same access-notice guard as detail reads, in one query for pinned-place source cards.
    @Query("""select distinct notice.bunchId from BunchAccessNotice notice
        where notice.recipientMemberId = :memberId and notice.expiresAt > :now
        and not exists (select membership.id from BunchMembership membership
            where membership.member.id = :memberId and membership.bunch.id = notice.bunchId
            and membership.status = com.bunch.bunch.domain.MembershipStatus.ACTIVE)""")
    fun unavailableBunchIds(memberId: Long, now: Instant): Set<Long>

    fun findAllByRecipientMemberIdAndBunchId(
        recipientMemberId: Long,
        bunchId: Long
    ): List<BunchAccessNotice>

    @Modifying
    @Query("delete from BunchAccessNotice notice where notice.expiresAt <= :now")
    fun deleteExpired(now: Instant): Int
}
