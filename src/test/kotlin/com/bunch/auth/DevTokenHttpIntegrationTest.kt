package com.bunch.auth

import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.Gender
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test", "local")
class DevTokenHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
    private val tokens: TokenProvider,
    private val sessions: RefreshTokenRepository,
    private val members: MemberRepository,
    private val jdbc: JdbcTemplate
) {
    private val prefix = "dev_${UUID.randomUUID().toString().take(8)}_"
    private val accounts = ConcurrentHashMap.newKeySet<String>()
    private val issuedSessions = ConcurrentLinkedQueue<Pair<String, Long>>()

    @AfterEach
    fun cleanOwnAccounts() {
        issuedSessions.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        accounts.forEach { account -> members.findByDevAccount(account)?.let { members.deleteById(it.id) } }
    }

    @Test
    fun `new account starts before onboarding and token authenticates existing APIs`() {
        val account = prefix + "new"
        val access = issue(account)
        val member = members.findByDevAccount(account)!!
        assertThat(tokens.getUserId(access)).isEqualTo(member.id)
        assertThat(tokens.getRole(access)).isEqualTo("ROLE_USER")
        assertThat(member.role).isEqualTo(MemberRole.ROLE_USER)
        assertThat(member.accountStatus).isEqualTo(AccountStatus.ACTIVE)
        assertThat(member.signupStatus).isEqualTo(SignupStatus.IN_PROGRESS)
        assertThat(member.nickname).isNull()
        assertThat(member.birthYear).isNull()
        assertThat(member.gender).isNull()
        assertThat(member.profileImageAsset).isNull()
        assertThat(member.completedAt).isNull()
        assertThat(member.registrationFingerprint).isNull()
        assertThat(count("member_consents", member.id)).isZero()
        assertThat(count("bunch_memberships", member.id)).isZero()
        mvc.perform(get("/api/v1/members/me/onboarding").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.memberId").value(member.id))
            .andExpect(jsonPath("$.data.signupStatus").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.termsAccepted").value(false))
            .andExpect(jsonPath("$.data.defaultBunchId").isEmpty)
        mvc.perform(get("/api/v1/bunches/name-availability")
            .param("name", "테스트")
            .header("Authorization", "Bearer $access"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.SIGNUP_INCOMPLETE.code))
    }

    @Test
    fun `same account preserves profile and onboarding progress with independent sessions`() {
        val account = prefix + "reuse"
        val first = issue(account)
        val member = members.findByDevAccount(account)!!
        val completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        member.nickname = prefix + "nick"
        member.signupStatus = SignupStatus.COMPLETED
        member.birthYear = 2000
        member.gender = Gender.FEMALE
        member.completedAt = completedAt
        member.registrationFingerprint = "fixture"
        members.saveAndFlush(member)
        listOf("SERVICE", "PRIVACY", "AGE_OVER_14").forEach {
            jdbc.update(
                "INSERT INTO member_consents (member_id, type, version, agreed_at) VALUES (?, ?, 'v1', now())",
                member.id,
                it
            )
        }
        val again = issue(account)
        assertThat(tokens.getUserId(again)).isEqualTo(member.id)
        assertThat(tokens.getSessionId(again)).isNotEqualTo(tokens.getSessionId(first))
        assertThat(sessions.isActive(tokens.getSessionId(first), member.id)).isTrue()
        val stored = members.findByDevAccount(account)!!
        assertThat(stored.nickname).isEqualTo(member.nickname)
        assertThat(stored.signupStatus).isEqualTo(SignupStatus.COMPLETED)
        assertThat(stored.birthYear).isEqualTo(2000)
        assertThat(stored.gender).isEqualTo(Gender.FEMALE)
        assertThat(stored.completedAt).isEqualTo(completedAt)
        assertThat(stored.registrationFingerprint).isEqualTo("fixture")
        assertThat(count("member_consents", member.id)).isEqualTo(3)
    }

    @Test
    fun `new names including case differences create separate accounts`() {
        val ids = listOf("tester1", "tester2", "Tester1").map { tokens.getUserId(issue(prefix + it)) }
        assertThat(ids.toSet()).hasSize(3)
    }

    @Test
    fun `concurrent first requests create one member and independent usable sessions`() {
        val account = prefix + "concurrent"
        val executor = Executors.newFixedThreadPool(4)
        try {
            val accesses = executor.invokeAll((1..8).map { Callable { issue(account) } })
                .map { it.get(15, TimeUnit.SECONDS) }
            val memberIds = accesses.map { tokens.getUserId(it) }.toSet()
            assertThat(memberIds).hasSize(1)
            assertThat(accesses.map { tokens.getSessionId(it) }.toSet()).hasSize(8)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM members WHERE dev_account = ?", Long::class.java, account
            )).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `logout invalidates the issued token`() {
        val access = issue(prefix + "logout")
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk)
        mvc.perform(get("/api/v1/members/me/onboarding").header("Authorization", "Bearer $access"))
            .andExpect(status().isUnauthorized)
    }

    @ParameterizedTest
    @ValueSource(strings = ["SUSPENDED", "WITHDRAWN"])
    fun `inactive accounts cannot receive another token`(accountStatus: String) {
        val account = prefix + "inactive"
        issue(account)
        val member = members.findByDevAccount(account)!!
        member.accountStatus = AccountStatus.valueOf(accountStatus)
        members.saveAndFlush(member)
        mvc.perform(post("/api/v1/auth/dev/token").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(mapOf("account" to account))))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.UNAUTHORIZED.code))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "tester-1", "tester.1", "테스터", "abcdefghijklmnopqrstuvwxyz1234567"])
    fun `invalid account names are rejected without creating members`(account: String) {
        val before = members.count()
        mvc.perform(post("/api/v1/auth/dev/token").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(mapOf("account" to account))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_INPUT.code))
        assertThat(members.count()).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"account\":null}"])
    fun `missing account names are rejected`(body: String) {
        mvc.perform(post("/api/v1/auth/dev/token").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_JSON.code))
    }

    private fun issue(account: String): String {
        accounts.add(account)
        val body = mvc.perform(post("/api/v1/auth/dev/token").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(mapOf("account" to account))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn().response.contentAsString
        val access = json.readTree(body).path("data").path("accessToken").asText()
        val memberId = tokens.getUserId(access)
        val sessionId = tokens.getSessionId(access)
        issuedSessions.add(sessionId to memberId)
        assertThat(tokens.isAccessToken(access)).isTrue()
        assertThat(sessions.isActive(sessionId, memberId)).isTrue()
        return access
    }

    private fun count(table: String, memberId: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE member_id = ?", Long::class.java, memberId)!!
}
