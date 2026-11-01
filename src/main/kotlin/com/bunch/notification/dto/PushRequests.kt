package com.bunch.notification.dto

import com.bunch.notification.domain.PushAuthorizationStatus
import com.bunch.notification.domain.PushDeviceEnvironment

data class PushDeviceRegistrationRequest(
    val deviceToken: String,
    val environment: PushDeviceEnvironment,
    val authorizationStatus: PushAuthorizationStatus
)

data class PushPreferencePatchRequest(
    val enabled: Boolean? = null,
    val types: Map<String, Boolean>? = null
)
