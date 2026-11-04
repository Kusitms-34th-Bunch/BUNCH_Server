package com.bunch.notification.domain

import com.bunch.notification.dto.PushPayload
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface PushJobRepository : JpaRepository<PushJob, UUID> {
    fun existsByRecipientMemberIdAndDeviceIdAndDedupeKey(
        recipientMemberId: Long,
        deviceId: Long,
        dedupeKey: String
    ): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findFirstByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAscCreatedAtAscIdAsc(
        status: PushJobStatus,
        now: Instant
    ): PushJob?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from PushJob j where j.id = :id")
    fun findLocked(id: UUID): PushJob?

    @Modifying
    @Query(
        value = """
            UPDATE push_jobs
            SET status = 'CANCELLED', last_error_code = :reason
            WHERE status = 'PENDING'
              AND recipient_member_id = :memberId
              AND type = 'COMMENT_REMINDER'
              AND payload -> 'target' ->> 'bundleId' = CAST(:bundleId AS text)
        """,
        nativeQuery = true
    )
    fun cancelPendingCommentReminderForMember(bundleId: Long, memberId: Long, reason: String): Int

    @Modifying
    @Query(
        value = """
            UPDATE push_jobs
            SET status = 'CANCELLED', last_error_code = :reason
            WHERE status = 'PENDING'
              AND type = 'COMMENT_REMINDER'
              AND payload -> 'target' ->> 'bundleId' = CAST(:bundleId AS text)
        """,
        nativeQuery = true
    )
    fun cancelPendingCommentReminders(bundleId: Long, reason: String): Int

    @Modifying
    @Query(
        value = """
            UPDATE push_jobs
            SET status = 'CANCELLED', last_error_code = :reason
            WHERE status = 'PENDING'
              AND recipient_member_id = :memberId
              AND bunch_id = :bunchId
              AND type = 'COMMENT_REMINDER'
        """,
        nativeQuery = true
    )
    fun cancelPendingCommentRemindersForBunchLeave(memberId: Long, bunchId: Long, reason: String): Int

    @Modifying
    @Query("""
        update PushJob j set j.status = com.bunch.notification.domain.PushJobStatus.CANCELLED,
            j.lastErrorCode = :reason
        where j.status = com.bunch.notification.domain.PushJobStatus.PENDING
          and j.recipientMemberId = :memberId
          and j.bunchId = :bunchId
          and j.type = com.bunch.notification.domain.NotificationType.MEMBER_REMOVED
    """)
    fun cancelPendingRemovalNoticeForRejoin(memberId: Long, bunchId: Long, reason: String): Int

    @Modifying
    @Query("""
        update PushJob j set j.status = com.bunch.notification.domain.PushJobStatus.CANCELLED,
            j.lastErrorCode = :reason
        where j.status = com.bunch.notification.domain.PushJobStatus.PENDING
          and j.recipientMemberId = :memberId
          and j.sessionId = :sessionId
    """)
    fun cancelPendingForSession(memberId: Long, sessionId: UUID, reason: String): Int

    @Modifying
    @Query("""
        update PushJob j set j.status = com.bunch.notification.domain.PushJobStatus.CANCELLED,
            j.lastErrorCode = :reason
        where j.status = com.bunch.notification.domain.PushJobStatus.PENDING
          and j.recipientMemberId = :memberId
          and j.deviceId = :deviceId
    """)
    fun cancelPendingForDevice(memberId: Long, deviceId: Long, reason: String): Int

    fun findAllByNotificationIdAndStatus(
        notificationId: Long,
        status: PushJobStatus
    ): List<PushJob>

    @Modifying
    @Query("""
        update PushJob j set j.payload = :payload
        where j.id = :id and j.status = com.bunch.notification.domain.PushJobStatus.PENDING
    """)
    fun updatePendingPayload(id: UUID, payload: PushPayload): Int

    @Modifying
    @Query("delete from PushJob j where j.status in (com.bunch.notification.domain.PushJobStatus.SENT, com.bunch.notification.domain.PushJobStatus.FAILED, com.bunch.notification.domain.PushJobStatus.CANCELLED) and j.createdAt <= :cutoff")
    fun deleteFinishedBefore(cutoff: Instant): Int
}
