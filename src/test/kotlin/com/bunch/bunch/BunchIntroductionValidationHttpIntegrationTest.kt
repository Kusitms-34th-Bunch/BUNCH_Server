package com.bunch.bunch

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BunchIntroductionValidationHttpIntegrationTest : BunchIntegrationSupport() {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var tokens: TokenProvider
    @Autowired private lateinit var sessions: RefreshTokenRepository

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanSessions() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    @Test
    fun `valid introduction checks are repeatable without creating or modifying a bunch`() {
        val owner = member()
        val bunch = create(owner)
        val before = jdbc.queryForMap("SELECT * FROM bunches WHERE id=?", bunch.id)
        val membershipBefore = jdbc.queryForList("SELECT * FROM bunch_memberships WHERE bunch_id=?", bunch.id)
        val auth = bearer(owner)
        listOf("  함께 📷\n기록해요  ", "가".repeat(50), "📷".repeat(50)).forEach {
            validate(auth, mapOf("introduction" to it))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty)
                .andExpect(jsonPath("$.error").isEmpty)
        }
        assertThat(count("bunches")).isEqualTo(1)
        assertThat(count("bunch_memberships")).isEqualTo(1)
        assertThat(jdbc.queryForMap("SELECT * FROM bunches WHERE id=?", bunch.id)).isEqualTo(before)
        assertThat(jdbc.queryForList("SELECT * FROM bunch_memberships WHERE bunch_id=?", bunch.id))
            .isEqualTo(membershipBefore)
    }

    @Test
    fun `prohibited introduction identifies only the field and corrected input can proceed`() {
        val auth = bearer(member())
        listOf("씨.발", "F U C K", "ㅅ\u200Bㅂ").forEach {
            validate(auth, mapOf("introduction" to it))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty)
                .andExpect(jsonPath("$.error.code").value("COMMON_003"))
                .andExpect(jsonPath("$.error.message").value("error.prohibited_word"))
                .andExpect(jsonPath("$.error.detail.fields[0]").value("introduction"))
                .andExpect(jsonPath("$.error.detail.fields.length()").value(1))
                .andExpect(jsonPath("$.error.detail.length()").value(1))
        }
        validate(auth, mapOf("introduction" to "함께 기록해요"))
            .andExpect(status().isOk)
        assertThat(count("bunches")).isZero()
        assertThat(count("bunch_memberships")).isZero()
    }

    @Test
    fun `blank overlong and missing introduction keep the existing validation and JSON contracts`() {
        val auth = bearer(member())
        listOf(" \n ", "가".repeat(51), "📷".repeat(51))
            .forEach {
                validate(auth, mapOf("introduction" to it))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"))
            }
        listOf(emptyMap<String, String>(), mapOf("introduction" to null)).forEach {
            validate(auth, it)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("COMMON_002"))
        }
    }

    @Test
    fun `validation requires an authenticated completed account`() {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("introduction" to "함께 기록해요"))))
            .andExpect(status().isUnauthorized)
        validate(bearer(member(completed = false)), mapOf("introduction" to "fuck"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("AUTH_015"))
    }

    @Test
    fun `prevalidation cannot bypass the final creation check with changed input`() {
        val owner = member()
        val auth = bearer(owner)
        validate(auth, mapOf("introduction" to "함께 기록해요"))
            .andExpect(status().isOk)
        mvc.perform(post("/api/v1/bunches")
            .header(HttpHeaders.AUTHORIZATION, auth)
            .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(request().copy(introduction = "fuck"))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_003"))
            .andExpect(jsonPath("$.error.detail.fields[0]").value("introduction"))
        assertThat(count("bunches")).isZero()
        assertThat(count("bunch_memberships")).isZero()
    }

    private fun validate(auth: String, body: Any) = mvc.perform(post(PATH)
        .header(HttpHeaders.AUTHORIZATION, auth)
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(body)))

    private fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return "Bearer ${pair.accessToken}"
    }

    private companion object {
        const val PATH = "/api/v1/bunches/introduction-validation"
    }
}
