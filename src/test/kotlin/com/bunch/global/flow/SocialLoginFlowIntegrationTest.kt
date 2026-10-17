package com.bunch.global.flow

import com.bunch.global.oidc.AuthProvider
import com.bunch.member.domain.ExternalIdentityRepository
import com.bunch.member.domain.MemberRepository
import com.bunch.global.security.TokenProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialLoginFlowIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
    private val tokens: TokenProvider,
    private val identities: ExternalIdentityRepository,
    private val members: MemberRepository
) {
    companion object {
        private val key = RSAKeyGenerator(2048).keyID("flow-key").generate()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/keys") { exchange ->
                val bytes = JWKSet(key.toPublicJWK()).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        @JvmStatic @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            listOf("apple", "google", "kakao").forEach { provider ->
                registry.add("auth.$provider.jwks-uri") { "http://127.0.0.1:${server.address.port}/keys" }
            }
        }
        @JvmStatic @AfterAll fun stop() { server.stop(0) }
    }

    @Test
    fun `all providers complete first and repeated login refresh replay rejection and logout`() {
        AuthProvider.entries.forEach { provider ->
            val subject = "flow-${UUID.randomUUID()}"
            val nonce = UUID.randomUUID().toString()
            val idToken = signedToken(provider, subject, nonce)
            val first = login(provider, idToken, nonce)
            val memberId = tokens.getUserId(first.first)
            assertThat(members.findById(memberId).orElseThrow().email).isNull()
            assertThat(identities.findByProviderAndProviderSubject(provider, subject)?.member?.id).isEqualTo(memberId)
            requestLogin(provider, idToken, nonce).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_011"))
            // A different serialization of the same signature must never bypass replay protection.
            requestLogin(provider, "$idToken=", nonce).andExpect(status().isBadRequest())
            val again = login(provider, signedToken(provider, subject, nonce), nonce)
            assertThat(tokens.getUserId(again.first)).isEqualTo(memberId)
            reissue(first.second).andExpect(status().isUnauthorized())
            val rotated = reissue(again.second).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn().response.contentAsString
            val rotatedRefresh = json.readTree(rotated).path("data").path("refreshToken").asText()
            reissue(again.second).andExpect(status().isUnauthorized())
            mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer ${again.first}"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
            reissue(rotatedRefresh).andExpect(status().isUnauthorized())
        }
    }

    private fun signedToken(provider: AuthProvider, subject: String, nonce: String): String {
        val issuer = when (provider) {
            AuthProvider.APPLE -> "https://appleid.apple.com"
            AuthProvider.GOOGLE -> "https://accounts.google.com"
            AuthProvider.KAKAO -> "https://kauth.kakao.com"
        }
        val now = Instant.now()
        val builder = JWTClaimsSet.Builder().issuer(issuer).subject(subject)
            .audience("test-${provider.name.lowercase()}-client")
            .issueTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString())
        if (provider != AuthProvider.GOOGLE) builder.claim("nonce", if (provider == AuthProvider.APPLE)
            MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray()).joinToString("") { "%02x".format(it) } else nonce)
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("flow-key").build(), builder.build())
            .apply { sign(RSASSASigner(key)) }.serialize()
    }
    private fun requestLogin(provider: AuthProvider, token: String, nonce: String) =
        mvc.perform(post("/api/v1/auth/${provider.name.lowercase()}/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(if (provider == AuthProvider.GOOGLE) mapOf("idToken" to token)
                else mapOf("idToken" to token, "nonce" to nonce))))
    private fun login(provider: AuthProvider, token: String, nonce: String): Pair<String, String> {
        val body = requestLogin(provider, token, nonce).andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie")).andReturn().response.contentAsString
        val data = json.readTree(body).path("data")
        return data.path("accessToken").asText() to data.path("refreshToken").asText()
    }
    private fun reissue(refresh: String) = mvc.perform(post("/api/v1/auth/reissue").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(mapOf("refreshToken" to refresh))))
}
