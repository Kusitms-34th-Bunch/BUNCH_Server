package com.bunch.global.security

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val tokenProvider: TokenProvider,
    private val refreshTokens: RefreshTokenRepository
) {
    // Logout only uses the authenticated subject and Redis; no persisted member is required.
    private val userId = UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE).coerceAtLeast(1L)

    @AfterEach
    fun removeOwnRefreshToken() {
        refreshTokens.delete(userId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["apple", "google", "kakao"])
    fun `public login rejects blank id token with validation error`(provider: String) {
        listOf("", "   ").forEach { idToken ->
            postJson("/api/v1/auth/$provider/login", """{"idToken":"$idToken","nonce":"test-nonce"}""")
                .expectError(ErrorCode.INVALID_INPUT)
                .andExpect(jsonPath("$.error.detail.field").value("idToken"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["apple", "google", "kakao"])
    fun `public login rejects missing required id token`(provider: String) {
        postJson("/api/v1/auth/$provider/login", """{"nonce":"test-nonce"}""")
            .expectError(ErrorCode.INVALID_JSON)
    }

    @ParameterizedTest
    @ValueSource(strings = ["apple", "kakao"])
    fun `nonce providers reject missing nonce before token verification`(provider: String) {
        postJson("/api/v1/auth/$provider/login", """{"idToken":"not-a-jwt"}""")
            .expectError(ErrorCode.INVALID_JSON)
    }

    @ParameterizedTest
    @ValueSource(strings = ["apple", "kakao"])
    fun `nonce providers reject blank nonce before token verification`(provider: String) {
        listOf("", "   ").forEach { nonce ->
            postJson("/api/v1/auth/$provider/login", """{"idToken":"not-a-jwt","nonce":"$nonce"}""")
                .expectError(ErrorCode.INVALID_INPUT)
                .andExpect(jsonPath("$.error.detail.field").value("nonce"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["apple", "google", "kakao"])
    fun `public login returns clean malformed token error without external requests`(provider: String) {
        // Omitting nonce for Google proves its DTO accepts the request and reaches verification.
        val body = if (provider == "google") """{"idToken":"not-a-jwt"}"""
        else """{"idToken":"not-a-jwt","nonce":"test-nonce"}"""

        postJson("/api/v1/auth/$provider/login", body).expectError(ErrorCode.MALFORMED_JWT)
    }

    @Test
    fun `public reissue rejects missing request body`() {
        mvc.perform(post("/api/v1/auth/reissue").contentType(MediaType.APPLICATION_JSON))
            .expectError(ErrorCode.INVALID_JSON)
    }

    @Test
    fun `public reissue rejects blank refresh token`() {
        listOf("", "   ").forEach { refreshToken ->
            postJson("/api/v1/auth/reissue", """{"refreshToken":"$refreshToken"}""")
                .expectError(ErrorCode.INVALID_INPUT)
                .andExpect(jsonPath("$.error.detail.field").value("refreshToken"))
        }
    }

    @Test
    fun `logout requires authentication`() {
        mvc.perform(post("/api/v1/auth/logout")).expectError(ErrorCode.UNAUTHORIZED)
    }

    @Test
    fun `refresh JWT cannot authenticate logout or remove stored refresh token`() {
        val refreshToken = tokenProvider.createRefreshToken(userId)
        refreshTokens.save(userId, refreshToken, tokenProvider.getRefreshTokenValiditySeconds())

        mvc.perform(post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $refreshToken"))
            .expectError(ErrorCode.UNAUTHORIZED)

        assertThat(refreshTokens.get(userId)).isEqualTo(refreshToken)
    }

    @Test
    fun `access JWT logs out without cookies and repeated logout returns defined error`() {
        val pair = tokenProvider.generateToken(userId, "ROLE_USER")
        refreshTokens.save(userId, pair.refreshToken, tokenProvider.getRefreshTokenValiditySeconds())

        mvc.perform(post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))

        assertThat(refreshTokens.get(userId)).isNull()

        mvc.perform(post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
            .expectError(ErrorCode.ALREADY_LOGGED_OUT)
    }

    @Test
    fun `authenticated unknown resource returns not found rather than authentication error`() {
        val accessToken = tokenProvider.createAccessToken(userId, "ROLE_USER")

        mvc.perform(get("/api/v1/missing-resource-${UUID.randomUUID()}")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    private fun postJson(path: String, body: String): ResultActions =
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(error.code))
            .andExpect(jsonPath("$.error.message").value(error.messageKey))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
}
