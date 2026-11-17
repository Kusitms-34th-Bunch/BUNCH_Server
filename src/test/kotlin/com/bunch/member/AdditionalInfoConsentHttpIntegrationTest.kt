package com.bunch.member

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.member.domain.Gender
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AdditionalInfoConsentHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val members: MemberRepository,
    private val tokens: TokenProvider,
    private val sessions: RefreshTokenRepository
) {
    private val memberIds = mutableListOf<Long>()
    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanup() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        memberIds.forEach { members.deleteById(it) }
    }

    @Test
    fun `a member who did not agree sees no consent and no values`() {
        val access = account()

        read(access)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.consented").value(false))
            .andExpect(jsonPath("$.data.birthYear").isEmpty)
            .andExpect(jsonPath("$.data.gender").isEmpty)
            .andExpect(jsonPath("$.error").isEmpty)
    }

    @Test
    fun `agreeing answers with the new state and the lookup shows it`() {
        val access = account(birthYear = 2003, gender = Gender.FEMALE)

        change(access, """{"agreed":true,"version":"v1"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.consented").value(true))
            .andExpect(jsonPath("$.data.birthYear").value(2003))
            .andExpect(jsonPath("$.data.gender").value("FEMALE"))
        read(access)
            .andExpect(jsonPath("$.data.consented").value(true))
            .andExpect(jsonPath("$.data.birthYear").value(2003))
    }

    @Test
    fun `withdrawing deletes the values and the same request again answers the same`() {
        val (memberId, access) = accountWithId(birthYear = 2003, gender = Gender.FEMALE)
        change(access, """{"agreed":true,"version":"v1"}""").andExpect(status().isOk)

        repeat(2) {
            change(access, """{"agreed":false}""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.consented").value(false))
                .andExpect(jsonPath("$.data.birthYear").isEmpty)
                .andExpect(jsonPath("$.data.gender").isEmpty)
        }
        val saved = members.findById(memberId).get()
        assertThat(saved.birthYear).isNull()
        assertThat(saved.gender).isNull()
    }

    @Test
    fun `a request key from an app is not needed and is ignored`() {
        val access = account()

        mvc.perform(
            put("/api/v1/members/me/additional-info-consent")
                .header("Authorization", "Bearer $access")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"agreed":true,"version":"v1"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.consented").value(true))
    }

    @Test
    fun `an agreement to another version is a conflict and a missing field or a broken body is invalid`() {
        val access = account()

        change(access, """{"agreed":true,"version":"old"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("AUTH_016"))
        change(access, """{"agreed":true}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_001"))
        change(access, """{"version":"v1"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_001"))
        change(access, "{")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_002"))
        change(access, """{"agreed":"yes","version":"v1"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_002"))
        read(access).andExpect(jsonPath("$.data.consented").value(false))
    }

    @Test
    fun `a member who has not finished signing up is forbidden`() {
        val access = account(signup = SignupStatus.IN_PROGRESS)

        read(access)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("AUTH_015"))
        change(access, """{"agreed":true,"version":"v1"}""")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("AUTH_015"))
    }

    @Test
    fun `a request without a token is unauthorized`() {
        mvc.perform(get("/api/v1/members/me/additional-info"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
        mvc.perform(
            put("/api/v1/members/me/additional-info-consent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"agreed":false}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
    }

    private fun read(access: String): ResultActions = mvc.perform(
        get("/api/v1/members/me/additional-info").header("Authorization", "Bearer $access")
    )

    private fun change(access: String, body: String): ResultActions = mvc.perform(
        put("/api/v1/members/me/additional-info-consent")
            .header("Authorization", "Bearer $access")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    )

    private fun account(
        birthYear: Int? = null,
        gender: Gender? = null,
        signup: SignupStatus = SignupStatus.COMPLETED
    ): String = accountWithId(birthYear, gender, signup).second

    // The member ID and the access token of a member with a live session, which is removed after the test.
    private fun accountWithId(
        birthYear: Int? = null,
        gender: Gender? = null,
        signup: SignupStatus = SignupStatus.COMPLETED
    ): Pair<Long, String> {
        val member = members.saveAndFlush(
            Member(
                signupStatus = signup,
                nickname = "동의${UUID.randomUUID().toString().take(8)}",
                birthYear = birthYear,
                gender = gender
            )
        ).also { memberIds += it.id }
        val pair = tokens.generateToken(member.id, member.role.name)
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, member.id, pair.refreshToken, 3600)
        sessionIds += sessionId to member.id
        return member.id to pair.accessToken
    }
}
