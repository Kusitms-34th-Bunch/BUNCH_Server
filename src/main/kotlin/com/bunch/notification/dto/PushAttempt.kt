package com.bunch.notification.dto

import com.bunch.notification.domain.push.PushDeviceEnvironment
import java.time.Instant
import java.util.UUID

data class PushAttempt(
    val jobId: UUID,
    val attempt: Int,
    val deviceId: Long,
    val memberId: Long,
    val tokenHash: String,
    val registeredAt: Instant,
    val environment: PushDeviceEnvironment,
    val topic: String,
    val ciphertext: ByteArray,
    val payload: PushPayload,
    val expiresAt: Instant
)
