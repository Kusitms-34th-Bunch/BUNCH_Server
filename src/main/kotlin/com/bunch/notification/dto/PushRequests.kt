package com.bunch.notification.dto

import com.bunch.notification.domain.push.PushAuthorizationStatus
import com.bunch.notification.domain.push.PushDeviceEnvironment
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

data class PushDeviceRegistrationRequest(
    val deviceToken: String,
    val environment: PushDeviceEnvironment,
    val authorizationStatus: PushAuthorizationStatus
)

data class PushPreferencePatchRequest(
    @field:JsonDeserialize(using = PushBooleanDeserializer::class)
    val enabled: Boolean? = null,
    @field:JsonDeserialize(contentUsing = PushBooleanDeserializer::class)
    val categories: Map<String, Boolean>? = null
)
