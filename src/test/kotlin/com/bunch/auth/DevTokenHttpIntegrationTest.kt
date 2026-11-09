package com.bunch.auth

import com.bunch.auth.dto.AuthResponses
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.MemberRole
import com.bunch.member.domain.SignupStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test", "prod")
class DevTokenHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
    private val tokens: TokenProvider,
    private val sessions: RefreshTokenRepository,
    private val members: MemberRepository
) {
    private val memberIds = mutableSetOf<Long>()
    private val issuedSessions = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun clean() {
        issuedSessions.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        memberIds.forEach { members.deleteById(it) }
    }

    @Test
    fun `new member starts before onboarding and access authenticates existing APIs`() {
        val issued = issue()
        val member = members.findById(issued.memberId).get()
        assertThat(member.role).isEqualTo(MemberRole.ROLE_USER)
        assertThat(member.accountStatus).isEqualTo(AccountStatus.ACTIVE)
        assertThat(member.signupStatus).isEqualTo(SignupStatus.IN_PROGRESS)
        assertThat(member.nickname).isNull()
        assertThat(member.birthYear).isNull()
        assertThat(member.gender).isNull()
        assertThat(member.profileImageAsset).isNull()
        assertThat(member.completedAt).isNull()
        assertThat(member.registrationFingerprint).isNull()
        mvc.perform(get("/api/v1/members/me/onboarding").header("Authorization", "Bearer ${issued.accessToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.memberId").value(issued.memberId))
            .andExpect(jsonPath("$.data.signupStatus").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.termsAccepted").value(false))
            .andExpect(jsonPath("$.data.defaultBunchId").isEmpty)
        mvc.perform(get("/api/v1/bunches/name-availability").param("name", "테스트")
            .header("Authorization", "Bearer ${issued.accessToken}"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.SIGNUP_INCOMPLETE.code))
    }

    @Test
    fun `existing member keeps profile and registration state with a new session`() {
        val first = issue()
        val member = members.findById(first.memberId).get()
        member.nickname = "별명${UUID.randomUUID().toString().take(6)}"
        member.signupStatus = SignupStatus.COMPLETED
        members.saveAndFlush(member)
        val again = issue(member.id)
        assertThat(again.memberId).isEqualTo(member.id)
        assertThat(tokens.getSessionId(again.accessToken)).isNotEqualTo(tokens.getSessionId(first.accessToken))
        val stored = members.findById(member.id).get()
        assertThat(stored.nickname).isEqualTo(member.nickname)
        assertThat(stored.signupStatus).isEqualTo(SignupStatus.COMPLETED)
    }

    @Test
    fun `omitted or nonexistent member id creates a new regular member`() {
        val ids = listOf(issue().memberId, issue().memberId, issue(Long.MAX_VALUE).memberId)
        assertThat(ids.toSet()).hasSize(3)
        assertThat(ids).doesNotContain(Long.MAX_VALUE)
    }

    @Test
    fun `logout invalidates the issued access`() {
        val access = issue().accessToken
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk)
        mvc.perform(get("/api/v1/members/me/onboarding").header("Authorization", "Bearer $access"))
            .andExpect(status().isUnauthorized)
    }

    @ParameterizedTest
    @ValueSource(strings = ["SUSPENDED", "WITHDRAWN"])
    fun `inactive members cannot receive another token`(accountStatus: String) {
        val member = members.findById(issue().memberId).get()
        member.accountStatus = AccountStatus.valueOf(accountStatus)
        members.saveAndFlush(member)
        mvc.perform(post("/api/v1/auth/dev/token").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(mapOf("memberId" to member.id))))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.UNAUTHORIZED.code))
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `nonpositive member ids are rejected`(memberId: Long) {
        mvc.perform(post("/api/v1/auth/dev/token").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(mapOf("memberId" to memberId))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_INPUT.code))
    }

    private fun issue(memberId: Long? = null): AuthResponses.DevTokenResponse {
        val request = if (memberId == null) emptyMap() else mapOf("memberId" to memberId)
        val body = mvc.perform(post("/api/v1/auth/dev/token").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(request)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn().response.contentAsString
        val issued = json.treeToValue(json.readTree(body).path("data"), AuthResponses.DevTokenResponse::class.java)
        memberIds.add(issued.memberId)
        val sessionId = tokens.getSessionId(issued.accessToken)
        issuedSessions.add(sessionId to issued.memberId)
        assertThat(tokens.getUserId(issued.accessToken)).isEqualTo(issued.memberId)
        assertThat(sessions.isActive(sessionId, issued.memberId)).isTrue()
        return issued
    }
}
