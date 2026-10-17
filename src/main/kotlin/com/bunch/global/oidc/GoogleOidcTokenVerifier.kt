package com.bunch.global.oidc

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class GoogleOidcTokenVerifier(
    @Value("\${auth.google.client-id}")
    clientId: String,
    @Value("\${auth.google.jwks-uri:https://www.googleapis.com/oauth2/v3/certs}")
    jwksUri: String
) : SocialTokenVerifier {
    override val provider = AuthProvider.GOOGLE
    private val verifier = OidcVerifier(
        setOf("https://accounts.google.com", "accounts.google.com"),
        clientId,
        jwksUri,
        NonceMode.NONE
    )
    override fun verify(idToken: String, nonce: String?): SocialIdentity = verifier.verify(idToken, nonce)
}
