package com.bunch.global.oidc

import java.time.Instant

data class SocialIdentity(val subject: String, val email: String?, val acceptedUntil: Instant)
