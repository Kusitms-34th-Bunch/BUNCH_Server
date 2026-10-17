package com.bunch.global.oidc

import com.bunch.global.oidc.AuthProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class AppleOidcTokenVerifier(
    @Value("\${auth.apple.client-id}") clientId: String,
    @Value("\${auth.apple.jwks-uri:https://appleid.apple.com/auth/keys}") jwksUri: String
) : SocialTokenVerifier {
    override val provider = AuthProvider.APPLE
    private val verifier = OidcVerifier(setOf("https://appleid.apple.com"), clientId, jwksUri, OidcVerifier.NonceMode.SHA256)
    override fun verify(idToken: String, nonce: String?): SocialIdentity = verifier.verify(idToken, nonce)
}
