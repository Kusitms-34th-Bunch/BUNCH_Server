package com.bunch.notification.dto

import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import java.time.Instant

data class NotificationTarget(
    val kind: NotificationTargetKind,
    val bunchId: Long,
    val bundleId: Long? = null,
    val noticeId: Long? = null,
    val targetBunchId: Long? = null,
    val sourceBunchId: Long? = null,
    val commentId: Long? = null
)

data class NotificationItem(
    val id: Long,
    val type: NotificationType,
    val title: String,
    val body: String,
    val createdAt: Instant,
    val readAt: Instant?,
    val target: NotificationTarget
)

data class NotificationPage(
    val items: List<NotificationItem>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val hasNext: Boolean,
    val unreadCount: Long
)

data class NotificationRead(val id: Long, val readAt: Instant)

data class NotificationUnread(val unreadCount: Long)

data class NotificationOpen(
    val notificationId: Long,
    val readAt: Instant,
    val available: Boolean,
    val target: NotificationTarget?
)
