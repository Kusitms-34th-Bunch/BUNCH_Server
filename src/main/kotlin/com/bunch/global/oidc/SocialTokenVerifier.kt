package com.bunch.global.oidc

interface SocialTokenVerifier {
    val provider: AuthProvider
    fun verify(idToken: String, nonce: String? = null): SocialIdentity
}
