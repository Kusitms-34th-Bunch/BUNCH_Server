package com.bunch.notification.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface NotificationRepository : JpaRepository<Notification, Long> {
    // Bound the SQL result before applying page offsets, even for an arbitrarily large page value.
    @Query(value = """SELECT * FROM notifications
        WHERE recipient_member_id = :memberId AND bunch_id = :bunchId AND created_at > :cutoff
        ORDER BY created_at DESC, id DESC LIMIT 100""", nativeQuery = true)
    fun latest(memberId: Long, bunchId: Long, cutoff: Instant): List<Notification>

    fun countByRecipientMemberIdAndBunchIdAndCreatedAtAfterAndReadAtIsNull(
        memberId: Long,
        bunchId: Long,
        cutoff: Instant
    ): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""select n from Notification n where n.id = :id and n.recipientMemberId = :memberId
        and n.bunchId = :bunchId and n.createdAt > :cutoff""")
    fun findOwnedLocked(id: Long, memberId: Long, bunchId: Long, cutoff: Instant): Notification?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""update Notification n set n.readAt = :now where n.recipientMemberId = :memberId
        and n.bunchId = :bunchId and n.createdAt > :cutoff and n.readAt is null""")
    fun readAll(memberId: Long, bunchId: Long, cutoff: Instant, now: Instant): Int

    @Query(value = """SELECT * FROM notifications
        WHERE recipient_member_id = :memberId AND bunch_id = :bunchId AND type = 'DRAFT_ACTIVITY'
        AND target ->> 'bundleId' = CAST(:bundleId AS text) AND group_until > :now
        ORDER BY id DESC LIMIT 1 FOR UPDATE""", nativeQuery = true)
    fun openActivity(memberId: Long, bunchId: Long, bundleId: Long, now: Instant): Notification?

    @Modifying
    @Query("delete from Notification n where n.createdAt <= :cutoff")
    fun deleteExpired(cutoff: Instant): Int

    @Modifying(flushAutomatically = true)
    @Query("delete from Notification n where n.recipientMemberId = :memberId")
    fun deleteByRecipientMemberId(memberId: Long): Int
}
