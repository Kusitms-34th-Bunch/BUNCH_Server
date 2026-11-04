package com.bunch.notification.dto

import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.push.PushAuthorizationStatus
import com.bunch.notification.domain.push.PushDeviceEnvironment
import java.time.Instant
import java.util.UUID

data class PushDeviceResponse(
    val installationId: UUID,
    val environment: PushDeviceEnvironment,
    val authorizationStatus: PushAuthorizationStatus,
    val registeredAt: Instant
)

data class PushPreferenceResponse(
    val enabled: Boolean,
    val types: Map<NotificationType, Boolean>
)
