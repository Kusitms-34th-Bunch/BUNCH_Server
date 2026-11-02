package com.bunch.photo

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * What the photo tests that call the API over HTTP share: a signed-in caller, a bunch with members and the check
 * of an error response. A subclass adds @SpringBootTest and @AutoConfigureMockMvc, which provide [mvc].
 */
abstract class PhotoHttpTestSupport : BunchIntegrationSupport() {
    @Autowired
    protected lateinit var mvc: MockMvc

    @Autowired
    protected lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanPhotoHttpSessions() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    /** The Authorization header of a member with a live session, which is removed after the test. */
    protected fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return "Bearer ${pair.accessToken}"
    }

    /** The owner's member ID and the ID of the bunch the owner created. */
    protected fun bunchWithOwner(): Pair<Long, Long> {
        val ownerId = member()
        return ownerId to create(ownerId).id
    }

    /** A new member who joined the bunch through an invitation of its owner. */
    protected fun joined(ownerId: Long, bunchId: Long): Long {
        val memberId = member()
        join(memberId, issue(ownerId, bunchId))
        return memberId
    }

    protected fun MvcResult.data(): JsonNode = mapper.readTree(response.contentAsString).path("data")

    protected fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(error.code))
            .andExpect(jsonPath("$.error.message").value(error.messageKey))
}
