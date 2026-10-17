package com.bunch.auth.oidc

import com.bunch.member.AuthProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class KakaoOidcTokenVerifier(
    @Value("\${auth.kakao.client-id}") clientId: String,
    @Value("\${auth.kakao.jwks-uri:https://kauth.kakao.com/.well-known/jwks.json}") jwksUri: String
) : SocialTokenVerifier {
    override val provider = AuthProvider.KAKAO
    private val verifier = OidcVerifier(setOf("https://kauth.kakao.com"), clientId, jwksUri, OidcVerifier.NonceMode.RAW)
    override fun verify(idToken: String, nonce: String?): SocialIdentity = verifier.verify(idToken, nonce)
}
