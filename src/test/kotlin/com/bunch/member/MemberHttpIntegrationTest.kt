package com.bunch.member

import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.MembershipStatus
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.dto.BunchResponse
import com.bunch.bunch.dto.CreateBunchRequest
import com.bunch.bunch.service.BunchService
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenPair
import com.bunch.global.security.TokenProvider
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.Gender
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class MemberHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val members: MemberRepository,
    private val tokens: TokenProvider,
    private val sessions: RefreshTokenRepository,
    private val bunches: BunchService,
    private val jdbc: JdbcTemplate,
    private val imageAssets: ImageAssetRepository
) {
    private val memberIds = mutableListOf<Long>()
    private val sessionIds = mutableListOf<Pair<String, Long>>()
    private val imageAssetIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        sessionIds.forEach { (sid, id) -> sessions.delete(sid, id) }
        memberIds.forEach { jdbc.update("DELETE FROM bunches WHERE created_by = ?", it) }
        memberIds.forEach { members.deleteById(it) }
        imageAssetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        imageAssetIds.clear()
    }

    @ParameterizedTest
    @EnumSource(Gender::class)
    fun `returns only authenticated member profile even when another ID is supplied`(gender: Gender) {
        val member = account(
            Member(
                signupStatus = SignupStatus.COMPLETED,
                nickname = "본인 ${UUID.randomUUID().toString().take(6)}",
                profileImageAsset = imageAsset("private-image-id", "https://example.com/profile.jpg"),
                birthYear = 2000,
                gender = gender
            )
        )
        val other = account(Member(nickname = "다른 ${UUID.randomUUID().toString().take(6)}"))
        val pair = session(member)

        mvc.perform(
            get("/api/v1/members/me")
                .param("memberId", other.id.toString())
                .header("Authorization", "Bearer ${pair.accessToken}")
        )
            .andExpect(status().isOk)
            .andExpect(content().json("""
                {
                  "success": true,
                  "data": {
                    "memberId": ${member.id},
                    "nickname": "${member.nickname}",
                    "imageUrl": "https://example.com/profile.jpg",
                    "birthYear": 2000,
                    "gender": "$gender",
                    "defaultBunch": null
                  },
                  "error": null
                }
            """.trimIndent(), JsonCompareMode.STRICT))
    }

    @Test
    fun `pending account without terms can read explicitly null profile fields`() {
        val member = account()
        val pair = session(member)

        profile(pair.accessToken)
            .andExpect(status().isOk)
            .andExpect(content().json("""
                {
                  "success": true,
                  "data": {
                    "memberId": ${member.id},
                    "nickname": null,
                    "imageUrl": null,
                    "birthYear": null,
                    "gender": null,
                    "defaultBunch": null
                  },
                  "error": null
                }
            """.trimIndent(), JsonCompareMode.STRICT))
    }

    @Test
    fun `completed account can read default image and unselected demographics as null`() {
        val member = account(
            Member(signupStatus = SignupStatus.COMPLETED, nickname = "선택 ${UUID.randomUUID().toString().take(6)}")
        )
        val pair = session(member)

        profile(pair.accessToken)
            .andExpect(status().isOk)
            .andExpect(content().json("""
                {
                  "success": true,
                  "data": {
                    "memberId": ${member.id},
                    "nickname": "${member.nickname}",
                    "imageUrl": null,
                    "birthYear": null,
                    "gender": null,
                    "defaultBunch": null
                  },
                  "error": null
                }
            """.trimIndent(), JsonCompareMode.STRICT))
    }

    @ParameterizedTest
    @EnumSource(MembershipStatus::class, names = ["LEFT", "REMOVED"])
    fun `default bunch returns display fields and falls back after membership or bunch removal`(
        inactiveStatus: MembershipStatus
    ) {
        val member = account(Member(signupStatus = SignupStatus.COMPLETED))
        val first = bunch(member)
        val second = bunch(member)
        bunch(account(Member(signupStatus = SignupStatus.COMPLETED)))
        val pair = session(member)
        jdbc.update(
            "UPDATE bunch_memberships SET last_used_at = ? WHERE member_id = ?",
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
            member.id
        )

        profile(pair.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.defaultBunch.id").value(second.id))

        val firstImage = imageAsset("bunch-image-id", "https://example.com/bunch.jpg")
        jdbc.update("UPDATE bunches SET profile_image_asset_id = ? WHERE id = ?", firstImage.id, first.id)
        bunches.updateLastUsed(member.id, first.id)

        profile(pair.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.defaultBunch.length()").value(4))
            .andExpect(jsonPath("$.data.defaultBunch.id").value(first.id))
            .andExpect(jsonPath("$.data.defaultBunch.name").value(first.name))
            .andExpect(jsonPath("$.data.defaultBunch.characterType").value(first.characterType.name))
            .andExpect(jsonPath("$.data.defaultBunch.imageUrl").value("https://example.com/bunch.jpg"))
            .andExpect(jsonPath("$.data.signupStatus").doesNotExist())
        mvc.perform(get("/api/v1/members/me/onboarding").header("Authorization", "Bearer ${pair.accessToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.signupStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.data.defaultBunchId").value(first.id))

        jdbc.update(
            "UPDATE bunch_memberships SET status = ? WHERE member_id = ? AND bunch_id = ?",
            inactiveStatus.name,
            member.id,
            first.id
        )

        profile(pair.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.defaultBunch.id").value(second.id))

        jdbc.update("DELETE FROM bunches WHERE id = ?", second.id)

        profile(pair.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.defaultBunch").isEmpty)
    }

    @ParameterizedTest
    @EnumSource(AccountStatus::class, names = ["SUSPENDED", "WITHDRAWN"])
    fun `inactive account is rejected and current session is revoked`(accountStatus: AccountStatus) {
        val member = account(Member(accountStatus = accountStatus))
        val pair = session(member)

        profile(pair.accessToken).expectUnauthorized()

        assertThat(sessions.isActive(tokens.getSessionId(pair.accessToken), member.id)).isFalse()
    }

    @Test
    fun `missing account is rejected and current session is revoked`() {
        val member = account()
        val pair = session(member)
        members.deleteById(member.id)
        memberIds.remove(member.id)

        profile(pair.accessToken).expectUnauthorized()

        assertThat(sessions.isActive(tokens.getSessionId(pair.accessToken), member.id)).isFalse()
    }

    @Test
    fun `unauthenticated request is rejected`() {
        mvc.perform(get("/api/v1/members/me")).expectUnauthorized()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "not-a-jwt"])
    fun `invalid access token is rejected`(access: String) {
        profile(access).expectUnauthorized()
    }

    @Test
    fun `refresh token cannot authenticate profile request`() {
        val member = account()
        val pair = session(member)

        profile(pair.refreshToken).expectUnauthorized()

        assertThat(sessions.isActive(tokens.getSessionId(pair.accessToken), member.id)).isTrue()
    }

    @Test
    fun `revoked session cannot read profile`() {
        val member = account()
        val pair = session(member)
        sessions.delete(tokens.getSessionId(pair.accessToken), member.id)

        profile(pair.accessToken).expectUnauthorized()
    }

    private fun account(member: Member = Member()): Member = members.saveAndFlush(member).also {
        memberIds += it.id
    }

    private fun imageAsset(publicId: String, url: String): ImageAsset = imageAssets.saveAndFlush(
        ImageAsset(
            publicId = "$publicId/${UUID.randomUUID()}",
            url = url
        )
    ).also { imageAssetIds += it.id }

    private fun bunch(member: Member): BunchResponse = bunches.create(
        member.id,
        UUID.randomUUID(),
        CreateBunchRequest(
            name = "번치 ${UUID.randomUUID().toString().take(6)}",
            introduction = "함께 기록해요",
            visibility = Visibility.PRIVATE,
            characterType = CharacterType.TYPE_2
        )
    )

    private fun session(member: Member): TokenPair {
        val pair = tokens.generateToken(member.id, member.role.name)
        val sid = tokens.getSessionId(pair.accessToken)
        sessions.save(sid, member.id, pair.refreshToken, 3600)
        sessionIds += sid to member.id
        return pair
    }

    private fun profile(access: String): ResultActions = mvc.perform(
        get("/api/v1/members/me").header("Authorization", "Bearer $access")
    )

    private fun ResultActions.expectUnauthorized(): ResultActions = andExpect(status().isUnauthorized)
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data").isEmpty)
        .andExpect(jsonPath("$.error.code").value("AUTH_001"))
}
