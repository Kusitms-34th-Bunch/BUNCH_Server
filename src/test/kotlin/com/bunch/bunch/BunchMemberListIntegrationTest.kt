package com.bunch.bunch

import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BunchMemberListIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanUpMemberListFixtures() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
        // The members are deleted by the superclass hook, so release the test images first.
        jdbc.update(
            """UPDATE members SET profile_image_asset_id = NULL WHERE profile_image_asset_id IN
                (SELECT id FROM image_assets WHERE public_id LIKE 'test-member-image/%')"""
        )
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-member-image/%'")
    }

    @Test
    fun `lists the active members with the main admin first and then by nickname`() {
        val ownerId = member().also { nickname(it, "하하") }
        val bunchId = create(ownerId).id
        val third = joined(ownerId, bunchId, "다다")
        val first = joined(ownerId, bunchId, "가가")
        val second = joined(ownerId, bunchId, "나나")

        members(third, bunchId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.length()").value(4))
            .andExpect(jsonPath("$.data[0].memberId").value(ownerId))
            .andExpect(jsonPath("$.data[0].nickname").value("하하"))
            .andExpect(jsonPath("$.data[0].role").value("OWNER"))
            .andExpect(jsonPath("$.data[1].memberId").value(first))
            .andExpect(jsonPath("$.data[1].nickname").value("가가"))
            .andExpect(jsonPath("$.data[1].role").value("MEMBER"))
            .andExpect(jsonPath("$.data[2].memberId").value(second))
            .andExpect(jsonPath("$.data[2].nickname").value("나나"))
            .andExpect(jsonPath("$.data[3].memberId").value(third))
            .andExpect(jsonPath("$.data[3].nickname").value("다다"))
    }

    @Test
    fun `a member without a profile image has a null image URL and the image URL is returned otherwise`() {
        val ownerId = member()
        val bunchId = create(ownerId).id
        val withImage = joined(ownerId, bunchId, "가가")
        val imageUrl = "https://res.cloudinary.com/test/image/upload/test-member-image/profile.jpg"
        profileImage(withImage, imageUrl)

        members(ownerId, bunchId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].memberId").value(ownerId))
            .andExpect(jsonPath("$.data[0].imageUrl").doesNotExist())
            .andExpect(jsonPath("$.data[1].memberId").value(withImage))
            .andExpect(jsonPath("$.data[1].imageUrl").value(imageUrl))
    }

    @Test
    fun `only the member ID nickname image and role are returned`() {
        val ownerId = member()
        val bunchId = create(ownerId).id

        members(ownerId, bunchId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].birthYear").doesNotExist())
            .andExpect(jsonPath("$.data[0].gender").doesNotExist())
            .andExpect(jsonPath("$.data[0].signupStatus").doesNotExist())
            .andExpect(jsonPath("$.data[0].accountStatus").doesNotExist())
    }

    @Test
    fun `members who left or were removed are not listed`() {
        val ownerId = member()
        val bunchId = create(ownerId).id
        val stays = joined(ownerId, bunchId, "가가")
        val left = joined(ownerId, bunchId, "나나")
        val removed = joined(ownerId, bunchId, "다다")
        membershipStatus(bunchId, left, "LEFT")
        membershipStatus(bunchId, removed, "REMOVED")

        members(ownerId, bunchId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].memberId").value(ownerId))
            .andExpect(jsonPath("$.data[1].memberId").value(stays))
    }

    @Test
    fun `a member who left and joined again is listed once`() {
        val ownerId = member()
        val bunchId = create(ownerId).id
        val returning = joined(ownerId, bunchId, "가가")
        membershipStatus(bunchId, returning, "LEFT")
        jdbc.update(
            """INSERT INTO bunch_memberships
                (bunch_id, member_id, role, status, joined_at, role_granted_at, last_used_at, join_requests)
                VALUES (?, ?, 'MEMBER', 'ACTIVE', now(), now(), now(), '{}'::jsonb)""",
            bunchId,
            returning
        )

        members(ownerId, bunchId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[1].memberId").value(returning))
    }

    @Test
    fun `withdrawn accounts are not listed`() {
        val ownerId = member()
        val bunchId = create(ownerId).id
        val withdrawn = joined(ownerId, bunchId, "가가")
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", withdrawn)

        members(ownerId, bunchId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].memberId").value(ownerId))
    }

    @Test
    fun `members of another bunch are not listed`() {
        val ownerId = member()
        val bunchId = create(ownerId).id
        val otherOwnerId = member()
        create(otherOwnerId)

        members(ownerId, bunchId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].memberId").value(ownerId))
    }

    @Test
    fun `non members unfinished signups and unknown bunches are rejected`() {
        val ownerId = member()
        val bunchId = create(ownerId).id
        val outsiderId = member()
        val leftId = joined(ownerId, bunchId)
        membershipStatus(bunchId, leftId, "LEFT")
        val unfinishedId = member(completed = false)

        members(outsiderId, bunchId).expectError(ErrorCode.FORBIDDEN)
        members(leftId, bunchId).expectError(ErrorCode.FORBIDDEN)
        members(unfinishedId, bunchId).expectError(ErrorCode.SIGNUP_INCOMPLETE)
        members(ownerId, Long.MAX_VALUE).expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `a request without a token is unauthorized`() {
        val bunchId = create(member()).id

        mvc.perform(get(path(bunchId))).andExpect(status().isUnauthorized)
    }

    private fun members(memberId: Long, bunchId: Long): ResultActions =
        mvc.perform(get(path(bunchId)).header(HttpHeaders.AUTHORIZATION, bearer(memberId)))

    private fun path(bunchId: Long) = "/api/v1/bunches/$bunchId/members"

    private fun joined(ownerId: Long, bunchId: Long, nickname: String? = null): Long {
        val memberId = member()
        if (nickname != null) nickname(memberId, nickname)
        join(memberId, issue(ownerId, bunchId))
        return memberId
    }

    private fun nickname(memberId: Long, value: String) {
        jdbc.update("UPDATE members SET nickname = ? WHERE id = ?", value, memberId)
    }

    private fun membershipStatus(bunchId: Long, memberId: Long, status: String) {
        jdbc.update(
            "UPDATE bunch_memberships SET status = ? WHERE bunch_id = ? AND member_id = ?",
            status,
            bunchId,
            memberId
        )
    }

    private fun profileImage(memberId: Long, url: String) {
        val assetId = jdbc.queryForObject(
            "INSERT INTO image_assets (public_id, url, created_at) VALUES (?, ?, now()) RETURNING id",
            Long::class.java,
            "test-member-image/${UUID.randomUUID()}",
            url
        )!!
        jdbc.update("UPDATE members SET profile_image_asset_id = ? WHERE id = ?", assetId, memberId)
    }

    private fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return "Bearer ${pair.accessToken}"
    }

    private fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(error.code))
            .andExpect(jsonPath("$.error.message").value(error.messageKey))
}
