package com.bunch.notification.domain

import com.bunch.notification.dto.NotificationTarget
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "notifications")
class Notification(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val recipientMemberId: Long,

    @Column(nullable = false)
    val bunchId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val type: NotificationType,

    @Column(nullable = false, length = 200)
    val dedupeKey: String,

    @Column(nullable = false, columnDefinition = "text")
    val title: String,

    @Column(nullable = false, columnDefinition = "text")
    var body: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val target: NotificationTarget,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var activityData: NotificationActivity? = null,

    var groupUntil: Instant? = null,

    @Column(nullable = false)
    val createdAt: Instant,

    var readAt: Instant? = null
)
