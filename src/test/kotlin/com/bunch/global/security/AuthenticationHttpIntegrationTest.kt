package com.bunch.global.security

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import java.util.Base64
import java.util.Date
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.time.Instant
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val tokenProvider: TokenProvider,
    private val members: MemberRepository,
    private val mapper: ObjectMapper,
    private val redis: StringRedisTemplate,
    @Value("\${jwt.secret}") private val secret: String
) {
    @MockitoSpyBean
    private lateinit var refreshTokens: RefreshTokenRepository

    // Logout serializes device cleanup with registration using the real member row.
    private val userId = members.saveAndFlush(Member()).id

    private val sessionIds = mutableListOf<String>()

    @AfterEach
    fun removeOwnRefreshToken() {
        sessionIds.forEach { refreshTokens.delete(it, userId) }
        members.deleteById(userId)
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
    fun `active access session issues uncached sixty second WS token without changing session`() {
        val pair = newSession()
        val sid = tokenProvider.getSessionId(pair.accessToken)
        val sessionKey = "session:$sid"
        val stored = redis.opsForValue().get(sessionKey)
        val ttl = redis.getExpire(sessionKey, TimeUnit.MILLISECONDS)

        val result = mvc.perform(post("/api/v1/auth/ws-token")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.token").isString())
            .andExpect(jsonPath("$.data.expiresAt").isString())
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andReturn()
        val data = mapper.readTree(result.response.contentAsString).path("data")
        val token = data.path("token").asText()
        val claims = tokenProvider.parseClaims(token)

        assertThat(data.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("token", "expiresAt")
        assertThat(claims.subject).isEqualTo(userId.toString())
        assertThat(claims["sid"]).isEqualTo(sid)
        assertThat(claims["tokenType"]).isEqualTo("ws")
        assertThat(claims.audience).isEqualTo("bunch-ws")
        assertThat(claims.id).isNotBlank()
        assertThat(claims.expiration.time - claims.issuedAt.time).isEqualTo(60_000L)
        assertThat(Instant.parse(data.path("expiresAt").asText())).isEqualTo(claims.expiration.toInstant())
        assertThat(redis.opsForValue().get(sessionKey)).isEqualTo(stored)
        assertThat(redis.getExpire(sessionKey, TimeUnit.MILLISECONDS)).isBetween(1L, ttl)
        postJson("/api/v1/auth/reissue", """{"refreshToken":"${pair.refreshToken}"}""")
            .andExpect(status().isOk())
    }

    @Test
    fun `WS token issuance requires access token and an active session`() {
        mvc.perform(post("/api/v1/auth/ws-token")).expectError(ErrorCode.UNAUTHORIZED)
        val pair = newSession()
        mvc.perform(post("/api/v1/auth/ws-token")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.refreshToken}"))
            .expectError(ErrorCode.UNAUTHORIZED)
        refreshTokens.delete(tokenProvider.getSessionId(pair.accessToken), userId)
        mvc.perform(post("/api/v1/auth/ws-token")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
            .expectError(ErrorCode.UNAUTHORIZED)
    }

    @Test
    fun `WS token issuance returns unavailable if Redis fails after filter authentication`() {
        val pair = newSession()
        val sid = tokenProvider.getSessionId(pair.accessToken)
        val stored = redis.opsForValue().get("session:$sid")
        doReturn(true)
            .doThrow(DataAccessResourceFailureException("private connection details"))
            .`when`(refreshTokens).isActive(sid, userId)

        try {
            mvc.perform(post("/api/v1/auth/ws-token")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
                .expectError(ErrorCode.AUTH_SESSION_UNAVAILABLE)
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect { result ->
                    assertThat(result.response.contentAsString).doesNotContain("private connection details")
                }
            verify(refreshTokens, times(2)).isActive(sid, userId)
            assertThat(redis.opsForValue().get("session:$sid")).isEqualTo(stored)
        } finally {
            reset(refreshTokens)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["SUSPENDED", "WITHDRAWN"])
    fun `WS token issuance rejects inactive member without rotating session`(state: String) {
        val pair = newSession()
        val member = members.findById(userId).orElseThrow()
        member.accountStatus = AccountStatus.valueOf(state)
        members.saveAndFlush(member)

        mvc.perform(post("/api/v1/auth/ws-token")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
            .expectError(ErrorCode.UNAUTHORIZED)
        assertThat(refreshTokens.isActive(tokenProvider.getSessionId(pair.accessToken), userId)).isTrue()
    }

    @Test
    fun `WS token issuance rejects missing member despite active session`() {
        val pair = newSession()
        members.deleteById(userId)

        mvc.perform(post("/api/v1/auth/ws-token")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
            .expectError(ErrorCode.UNAUTHORIZED)
        assertThat(refreshTokens.isActive(tokenProvider.getSessionId(pair.accessToken), userId)).isTrue()
    }

    @Test
    fun `WS JWT cannot authorize REST or token issuance or refresh rotation`() {
        val pair = newSession()
        val token = signedWsToken(tokenProvider.getSessionId(pair.accessToken))

        listOf("/api/v1/auth/logout", "/api/v1/auth/ws-token").forEach { path ->
            mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
                .expectError(ErrorCode.UNAUTHORIZED)
        }
        mvc.perform(get("/api/v1/missing-resource")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .expectError(ErrorCode.UNAUTHORIZED)
        postJson("/api/v1/auth/reissue", """{"refreshToken":"$token"}""")
            .expectError(ErrorCode.UNAUTHORIZED)
        assertThat(refreshTokens.isActive(tokenProvider.getSessionId(pair.accessToken), userId)).isTrue()
    }

    @Test
    fun `WS bearer is rejected even on public token issuance endpoints`() {
        val pair = newSession()
        val token = signedWsToken(tokenProvider.getSessionId(pair.accessToken))
        listOf(
            "/api/v1/auth/dev/token" to """{"nickname":"ws-rejected-${UUID.randomUUID()}"}""",
            "/api/v1/auth/reissue" to """{"refreshToken":"${pair.refreshToken}"}""",
            "/api/v1/auth/google/login" to """{"idToken":"not-a-jwt"}"""
        ).forEach { (path, body) ->
            mvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .expectError(ErrorCode.UNAUTHORIZED)
        }
        assertThat(refreshTokens.isActive(tokenProvider.getSessionId(pair.accessToken), userId)).isTrue()
    }

    @Test
    fun `refresh JWT cannot authenticate logout or remove stored refresh token`() {
        val pair = newSession()
        val refreshToken = pair.refreshToken

        mvc.perform(post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $refreshToken"))
            .expectError(ErrorCode.UNAUTHORIZED)

        assertThat(refreshTokens.isActive(tokenProvider.getSessionId(refreshToken), userId)).isTrue()
    }

    @Test
    fun `access JWT logs out without cookies and immediately revokes current session only`() {
        val pair = newSession()
        val other = newSession()

        mvc.perform(post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))

        assertThat(refreshTokens.isActive(tokenProvider.getSessionId(pair.accessToken), userId)).isFalse()
        assertThat(refreshTokens.isActive(tokenProvider.getSessionId(other.accessToken), userId)).isTrue()

        mvc.perform(post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
            .expectError(ErrorCode.UNAUTHORIZED)
        mvc.perform(get("/api/v1/missing-resource")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
            .expectError(ErrorCode.UNAUTHORIZED)
        postJson("/api/v1/auth/reissue", """{"refreshToken":"${pair.refreshToken}"}""")
            .expectError(ErrorCode.UNAUTHORIZED)
        mvc.perform(post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${other.accessToken}"))
            .andExpect(status().isOk())
    }

    @Test
    fun `authenticated unknown resource returns not found rather than authentication error`() {
        val accessToken = newSession().accessToken

        mvc.perform(get("/api/v1/missing-resource-${UUID.randomUUID()}")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `authenticated legacy onboarding path returns not found`() {
        val accessToken = newSession().accessToken

        mvc.perform(get("/api/v1/auth/session")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `access requires a live session bound to signed member`() {
        val pair = tokenProvider.generateToken(userId, "ROLE_USER")
        val sid = tokenProvider.getSessionId(pair.accessToken)
        mvc.perform(post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
            .expectError(ErrorCode.UNAUTHORIZED)
        refreshTokens.save(sid, userId + 1, pair.refreshToken, 120)
        try {
            mvc.perform(post("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${pair.accessToken}"))
                .expectError(ErrorCode.UNAUTHORIZED)
            assertThat(refreshTokens.isActive(sid, userId + 1)).isTrue()
        } finally {
            refreshTokens.delete(sid, userId + 1)
        }
    }

    @Test
    fun `signed legacy JWT without sid cannot authenticate or reissue`() {
        listOf("access", "refresh").forEach { type ->
            val token = Jwts.builder().setSubject(userId.toString()).claim("role", "ROLE_USER")
                .claim("tokenType", type).setExpiration(Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)), SignatureAlgorithm.HS512).compact()
            mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
                .expectError(ErrorCode.UNAUTHORIZED)
            postJson("/api/v1/auth/reissue", """{"refreshToken":"$token"}""")
                .expectError(ErrorCode.UNAUTHORIZED)
        }
    }

    @Test
    fun `only single segment invitation GET is public`() {
        mvc.perform(get("/api/v1/invitations/unknown-${UUID.randomUUID()}"))
            .andExpect { result -> assertThat(result.response.status).isNotEqualTo(401) }
        mvc.perform(get("/api/v1/invitations/unknown/nested")).expectError(ErrorCode.UNAUTHORIZED)
        mvc.perform(post("/api/v1/invitations/verify")).expectError(ErrorCode.UNAUTHORIZED)
        mvc.perform(get("/error")).expectError(ErrorCode.UNAUTHORIZED)
        mvc.perform(get("/api/v1/members/me/onboarding")).expectError(ErrorCode.UNAUTHORIZED)
        mvc.perform(get("/api/v1/auth/terms")).expectError(ErrorCode.UNAUTHORIZED)
    }

    private fun newSession(): TokenPair {
        val pair = tokenProvider.generateToken(userId, "ROLE_USER")
        val sid = tokenProvider.getSessionId(pair.accessToken)
        sessionIds.add(sid)
        refreshTokens.save(sid, userId, pair.refreshToken, tokenProvider.getRefreshTokenValiditySeconds())
        return pair
    }

    private fun signedWsToken(sid: String): String {
        val now = Date()
        return Jwts.builder()
            .setSubject(userId.toString())
            .claim("sid", sid)
            .claim("tokenType", "ws")
            .setAudience("bunch-ws")
            .setId(UUID.randomUUID().toString())
            .setIssuedAt(now)
            .setExpiration(Date(now.time + 60_000L))
            .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)), SignatureAlgorithm.HS512)
            .compact()
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
