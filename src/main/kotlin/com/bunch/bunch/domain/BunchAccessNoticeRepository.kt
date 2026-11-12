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

    fun findAllByRecipientMemberIdAndBunchId(
        recipientMemberId: Long,
        bunchId: Long
    ): List<BunchAccessNotice>

    @Modifying
    @Query("delete from BunchAccessNotice notice where notice.expiresAt <= :now")
    fun deleteExpired(now: Instant): Int

    @Modifying(flushAutomatically = true)
    @Query("delete from BunchAccessNotice notice where notice.recipientMemberId = :memberId")
    fun deleteByRecipientMemberId(memberId: Long): Int
}
