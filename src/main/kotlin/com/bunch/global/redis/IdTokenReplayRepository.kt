package com.bunch.global.redis

import com.bunch.global.oidc.AuthProvider
import java.time.Instant

interface IdTokenReplayRepository {
    fun reserve(provider: AuthProvider, idToken: String, acceptedUntil: Instant): Boolean
}
