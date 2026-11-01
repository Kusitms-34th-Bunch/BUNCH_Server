package com.bunch.notification.dto

import com.bunch.notification.domain.NotificationType
import java.util.UUID

data class PushPayload(
    val aps: PushAps,
    val eventId: UUID,
    val type: NotificationType,
    val target: Map<String, Any?>
)

data class PushAps(
    val alert: PushAlert,
    val sound: String = "default"
)

data class PushAlert(
    val title: String,
    val body: String
)
