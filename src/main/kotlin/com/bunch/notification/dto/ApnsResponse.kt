package com.bunch.notification.dto

import java.time.Instant

data class ApnsResponse(
    val status: Int,
    val reason: String?,
    val retryAfter: Instant? = null
)
