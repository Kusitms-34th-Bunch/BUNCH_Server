package com.bunch.auth.oidc

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.AuthProvider
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

class OidcTokenVerifierTest {
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val wrongKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
    private lateinit var server: HttpServer
    private lateinit var verifiers: List<SocialTokenVerifier>
    private val nonce = "random-app-nonce"
    private fun issuer(provider: AuthProvider) = when (provider) {
        AuthProvider.APPLE -> "https://appleid.apple.com"
        AuthProvider.GOOGLE -> "https://accounts.google.com"
        AuthProvider.KAKAO -> "https://kauth.kakao.com"
    }
    @BeforeEach
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/jwks") { exchange ->
            val bytes = JWKSet(key.toPublicJWK()).toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val uri = "http://127.0.0.1:${server.address.port}/jwks"
        verifiers = listOf(AppleOidcTokenVerifier("client", uri), GoogleOidcTokenVerifier("client", uri), KakaoOidcTokenVerifier("client", uri))
    }
    @AfterEach fun teardown() { server.stop(0) }

    private fun token(provider: AuthProvider, change: (JWTClaimsSet.Builder) -> Unit = {}, signingKey: RSAKey = key): String {
        val now = Instant.now()
        val hashedNonce = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
        val builder = JWTClaimsSet.Builder().issuer(issuer(provider)).audience("client").subject("subject-1")
            .issueTime(Date.from(now.minusSeconds(10))).expirationTime(Date.from(now.plusSeconds(300)))
            .claim("email", "verified@example.com").claim("email_verified", true)
            .claim("nonce", if (provider == AuthProvider.APPLE) hashedNonce else nonce)
        change(builder)
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), builder.build())
            .apply { sign(RSASSASigner(signingKey)) }.serialize()
    }
    @Test fun `all providers verify signed claims and accepted expiry`() {
        verifiers.forEach { v ->
            val identity = v.verify(token(v.provider), nonce)
            assertEquals("subject-1", identity.subject)
            assertEquals("verified@example.com", identity.email)
            assertTrue(identity.acceptedUntil.isAfter(Instant.now().plusSeconds(350)))
            assertNull(v.verify(token(v.provider, { it.claim("email_verified", false) }), nonce).email)
            assertNull(v.verify(token(v.provider, { it.claim("email", null) }), nonce).email)
        }
    }
    @Test fun `all providers reject invalid cryptography and required claims`() {
        verifiers.forEach { v ->
            fun rejected(token: String, code: ErrorCode = ErrorCode.INVALID_SOCIAL_TOKEN) {
                assertEquals(code, assertThrows(BusinessException::class.java) { v.verify(token, nonce) }.errorCode)
            }
            rejected("malformed", ErrorCode.MALFORMED_JWT)
            rejected(token(v.provider, signingKey = wrongKey))
            rejected(token(v.provider, { it.issuer("https://attacker.example") }))
            rejected(token(v.provider, { it.audience("wrong-client") }))
            rejected(token(v.provider, { it.expirationTime(Date.from(Instant.now().minusSeconds(120))) }))
            rejected(token(v.provider, { it.issueTime(Date.from(Instant.now().plusSeconds(120))) }))
            rejected(token(v.provider, { it.issueTime(null) }))
            rejected(token(v.provider, { it.expirationTime(null) }))
            rejected(token(v.provider, { it.subject(" ") }))
            rejected(token(v.provider, { it.notBeforeTime(Date.from(Instant.now().plusSeconds(120))) }))
        }
    }
    @Test fun `nonce is mandatory and provider-specific while Google has none`() {
        verifiers.filter { it.provider != AuthProvider.GOOGLE }.forEach { v ->
            assertEquals(ErrorCode.NONCE_MISMATCH, assertThrows(BusinessException::class.java) {
                v.verify(token(v.provider), "wrong")
            }.errorCode)
            assertThrows(BusinessException::class.java) { v.verify(token(v.provider), null) }
            assertThrows(BusinessException::class.java) { v.verify(token(v.provider, { it.claim("nonce", null) }), nonce) }
        }
        val google = verifiers.single { it.provider == AuthProvider.GOOGLE }
        assertEquals("subject-1", google.verify(token(AuthProvider.GOOGLE), null).subject)
        assertEquals("subject-1", google.verify(token(AuthProvider.GOOGLE, { it.issuer("accounts.google.com") })).subject)
    }
    @Test fun `raw missing and wrong typed required claims are rejected before conversion`() {
        verifiers.forEach { verifier ->
            val valid = SignedJWT.parse(token(verifier.provider)).payload.toJSONObject()
            val mutations: List<(MutableMap<String, Any?>) -> Unit> = listOf(
                { it.remove("aud") }, { it["aud"] = null }, { it["aud"] = 7 },
                { it["aud"] = listOf("client", 7) }, { it["aud"] = emptyList<String>() },
                { it.remove("iat") }, { it["iat"] = "123" }, { it["iat"] = true },
                { it.remove("exp") }, { it["exp"] = "123" }, { it["exp"] = 1.5 },
                { it.remove("sub") }, { it["sub"] = 7 }, { it["sub"] = listOf("subject") }
            )
            mutations.forEach { mutate ->
                val raw = valid.toMutableMap()
                mutate(raw)
                val signed = JWSObject(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), Payload(raw))
                    .apply { sign(RSASSASigner(key)) }.serialize()
                assertEquals(ErrorCode.INVALID_SOCIAL_TOKEN,
                    assertThrows(BusinessException::class.java) { verifier.verify(signed, nonce) }.errorCode)
            }
        }
    }
    @Test fun `equivalent signature encodings are rejected rather than bypassing replay hash`() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        verifiers.forEach { verifier ->
            val signed = token(verifier.provider)
            val last = signed.last()
            val noncanonical = signed.dropLast(1) + alphabet[alphabet.indexOf(last) xor 1]
            listOf(signed + "=", signed + "!", noncanonical).forEach { encoded ->
                assertEquals(ErrorCode.MALFORMED_JWT,
                    assertThrows(BusinessException::class.java) { verifier.verify(encoded, nonce) }.errorCode)
            }
        }
    }
    @Test fun `unavailable JWKS returns provider unavailable`() {
        val unavailable = GoogleOidcTokenVerifier("client", "http://127.0.0.1:${server.address.port}/missing")
        assertEquals(ErrorCode.AUTH_PROVIDER_UNAVAILABLE, assertThrows(BusinessException::class.java) {
            unavailable.verify(token(AuthProvider.GOOGLE))
        }.errorCode)
    }
}
