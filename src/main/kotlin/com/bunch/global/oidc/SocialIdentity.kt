package com.bunch.global.oidc

import java.time.Instant

data class SocialIdentity(
    val subject: String,
    val acceptedUntil: Instant
)
