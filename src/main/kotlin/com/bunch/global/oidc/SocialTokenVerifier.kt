package com.bunch.global.oidc

import com.bunch.global.oidc.AuthProvider
import java.time.Instant

data class SocialIdentity(val subject: String, val email: String?, val acceptedUntil: Instant)

interface SocialTokenVerifier {
    val provider: AuthProvider
    fun verify(idToken: String, nonce: String? = null): SocialIdentity
}
