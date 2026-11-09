package com.bunch.member

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest(properties = ["support.open-chat-url=https://open.kakao.com/o/test-channel"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class SupportChannelHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val members: MemberRepository,
    private val tokens: TokenProvider,
    private val sessions: RefreshTokenRepository
) {
    private val memberIds = mutableListOf<Long>()
    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanup() {
        sessionIds.forEach { (sid, id) -> sessions.delete(sid, id) }
        memberIds.forEach { members.deleteById(it) }
    }

    @Test
    fun `a completed member receives only the configured open chat link`() {
        read(access()).andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                    {
                      "success": true,
                      "data": { "openChatUrl": "https://open.kakao.com/o/test-channel" },
                      "error": null
                    }
                    """.trimIndent(),
                    JsonCompareMode.STRICT
                )
            )
    }

    @Test
    fun `request without a token is unauthorized`() {
        mvc.perform(get("/api/v1/members/support-channel"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
    }

    @Test
    fun `an unfinished signup is forbidden`() {
        read(access(signup = SignupStatus.IN_PROGRESS))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("AUTH_015"))
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus::class, names = ["SUSPENDED", "WITHDRAWN"])
    fun `suspended and withdrawn accounts are unauthorized`(accountStatus: AccountStatus) {
        read(access(account = accountStatus))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
    }

    private fun read(access: String): ResultActions =
        mvc.perform(get("/api/v1/members/support-channel").header("Authorization", "Bearer $access"))

    private fun access(
        signup: SignupStatus = SignupStatus.COMPLETED,
        account: AccountStatus = AccountStatus.ACTIVE
    ): String {
        val member = members.saveAndFlush(
            Member(
                signupStatus = signup,
                accountStatus = account,
                nickname = "문의${UUID.randomUUID().toString().take(8)}"
            )
        ).also { memberIds += it.id }
        val pair = tokens.generateToken(member.id, member.role.name)
        val sid = tokens.getSessionId(pair.accessToken)
        sessions.save(sid, member.id, pair.refreshToken, 3600)
        sessionIds += sid to member.id
        return pair.accessToken
    }
}
