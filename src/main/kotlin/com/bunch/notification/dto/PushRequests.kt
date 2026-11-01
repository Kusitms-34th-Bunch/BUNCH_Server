package com.bunch.notification.dto

import com.bunch.notification.domain.PushAuthorizationStatus
import com.bunch.notification.domain.PushDeviceEnvironment
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
    val types: Map<String, Boolean>? = null
)
