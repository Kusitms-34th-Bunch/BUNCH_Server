package com.bunch.notification.domain.push

import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.PushPayload
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "push_jobs",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_push_jobs_recipient_device_dedupe",
            columnNames = ["recipient_member_id", "device_id", "dedupe_key"]
        )
    ]
)
class PushJob(
    @Id
    @Column(nullable = false)
    val id: UUID,

    @Column(name = "device_id")
    var deviceId: Long?,

    @Column(name = "recipient_member_id", nullable = false)
    val recipientMemberId: Long,

    @Column(name = "session_id", nullable = false)
    val sessionId: UUID,

    @Column(name = "bunch_id", nullable = false)
    val bunchId: Long,

    @Column(name = "notification_id")
    val notificationId: Long?,

    @Column(nullable = false, length = 200)
    val dedupeKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val type: NotificationType,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var payload: PushPayload,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: PushJobStatus = PushJobStatus.PENDING,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(name = "available_at", nullable = false)
    var availableAt: Instant,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Column(name = "last_error_code", length = 80)
    var lastErrorCode: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant
)
