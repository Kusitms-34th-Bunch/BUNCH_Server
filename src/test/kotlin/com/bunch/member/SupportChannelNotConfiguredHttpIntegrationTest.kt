package com.bunch.member

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

// The test profile leaves support.open-chat-url empty, like an environment that has not set it yet.
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class SupportChannelNotConfiguredHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val members: MemberRepository,
    private val tokens: TokenProvider,
    private val sessions: RefreshTokenRepository
) {
    private var memberId = 0L
    private var session: Pair<String, Long>? = null

    @AfterEach
    fun cleanup() {
        session?.let { (sid, id) -> sessions.delete(sid, id) }
        if (memberId != 0L) members.deleteById(memberId)
    }

    @Test
    fun `an unset link answers service unavailable instead of an empty link`() {
        val member = members.saveAndFlush(
            Member(signupStatus = SignupStatus.COMPLETED, nickname = "미설정${UUID.randomUUID().toString().take(8)}")
        )
        memberId = member.id
        val pair = tokens.generateToken(member.id, member.role.name)
        val sid = tokens.getSessionId(pair.accessToken)
        sessions.save(sid, member.id, pair.refreshToken, 3600)
        session = sid to member.id

        mvc.perform(get("/api/v1/members/support-channel").header("Authorization", "Bearer ${pair.accessToken}"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty)
            .andExpect(jsonPath("$.error.code").value("USER_004"))
    }
}
