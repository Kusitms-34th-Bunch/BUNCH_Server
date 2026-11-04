package com.bunch.notification.domain.push

enum class PushAuthorizationStatus {
    NOT_DETERMINED,
    DENIED,
    AUTHORIZED,
    PROVISIONAL;

    val canReceive: Boolean
        get() = this == AUTHORIZED || this == PROVISIONAL
}
