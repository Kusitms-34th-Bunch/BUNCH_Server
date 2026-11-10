package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "notification.push.apns.sandbox-topic=com.bunch.integration.test",
        "notification.push.apns.production-topic=com.bunch.integration.test.production",
        "notification.push.device-encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    ]
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PushPreferenceCategoryIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @BeforeEach
    fun cleanSettingsBeforeTest() {
        jdbc.update("DELETE FROM push_preferences")
    }

    @AfterEach
    fun cleanSettingsAndSessionsAfterTest() {
        jdbc.update("DELETE FROM push_preferences")
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    @Test
    fun `a member without saved settings sees every toggle on`() {
        val access = access(member())

        read(access)
            .expectOk()
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.categories.BUNDLE_NEWS").value(true))
            .andExpect(jsonPath("$.data.categories.MEMBER_ROLE").value(true))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(true))
            .andExpect(jsonPath("$.data.categories.REACTION").value(true))
            .andExpect(jsonPath("$.data.types").doesNotExist())
        assertThat(count("push_preferences")).isZero()
    }

    @Test
    fun `switching one category off keeps the others and the app push toggle`() {
        val access = access(member())

        change(access, """{"categories":{"FOLLOW":false}}""")
            .expectOk()
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(false))
            .andExpect(jsonPath("$.data.categories.BUNDLE_NEWS").value(true))
            .andExpect(jsonPath("$.data.categories.MEMBER_ROLE").value(true))
            .andExpect(jsonPath("$.data.categories.REACTION").value(true))
        read(access)
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(false))
            .andExpect(jsonPath("$.data.categories.REACTION").value(true))
    }

    @Test
    fun `the app push toggle keeps the category values while off and gives them back when on`() {
        val access = access(member())
        change(access, """{"categories":{"FOLLOW":false}}""").expectOk()

        change(access, """{"enabled":false}""")
            .expectOk()
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(false))
            .andExpect(jsonPath("$.data.categories.BUNDLE_NEWS").value(true))
        read(access)
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(false))

        change(access, """{"enabled":true}""")
            .expectOk()
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(false))
            .andExpect(jsonPath("$.data.categories.BUNDLE_NEWS").value(true))
    }

    @Test
    fun `a category can be saved while the app push toggle is off and the toggle stays off`() {
        val access = access(member())
        change(access, """{"enabled":false}""").expectOk()

        change(access, """{"categories":{"REACTION":false}}""")
            .expectOk()
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.categories.REACTION").value(false))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(true))
    }

    @Test
    fun `switching the last category off turns the app push toggle off`() {
        val access = access(member())

        listOf("BUNDLE_NEWS", "MEMBER_ROLE", "FOLLOW").forEach {
            change(access, """{"categories":{"$it":false}}""")
                .expectOk()
                .andExpect(jsonPath("$.data.enabled").value(true))
        }
        change(access, """{"categories":{"REACTION":false}}""")
            .expectOk()
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.categories.BUNDLE_NEWS").value(false))
            .andExpect(jsonPath("$.data.categories.MEMBER_ROLE").value(false))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(false))
            .andExpect(jsonPath("$.data.categories.REACTION").value(false))
        read(access).andExpect(jsonPath("$.data.enabled").value(false))
    }

    @Test
    fun `switching every category off in one request turns the app push toggle off even if it asks for on`() {
        val access = access(member())
        val everyCategoryOff = """{"BUNDLE_NEWS":false,"MEMBER_ROLE":false,"FOLLOW":false,"REACTION":false}"""

        change(access, """{"enabled":true,"categories":$everyCategoryOff}""")
            .expectOk()
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(false))
    }

    @Test
    fun `turning the app push on while every category is off turns every category on`() {
        val access = access(member())
        change(
            access,
            """{"categories":{"BUNDLE_NEWS":false,"MEMBER_ROLE":false,"FOLLOW":false,"REACTION":false}}"""
        ).expectOk().andExpect(jsonPath("$.data.enabled").value(false))

        change(access, """{"enabled":true}""")
            .expectOk()
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.categories.BUNDLE_NEWS").value(true))
            .andExpect(jsonPath("$.data.categories.MEMBER_ROLE").value(true))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(true))
            .andExpect(jsonPath("$.data.categories.REACTION").value(true))
    }

    @Test
    fun `turning one category on again after the app push toggle went off keeps the others off`() {
        val access = access(member())
        change(
            access,
            """{"categories":{"BUNDLE_NEWS":false,"MEMBER_ROLE":false,"FOLLOW":false,"REACTION":false}}"""
        ).expectOk()

        change(access, """{"enabled":true,"categories":{"FOLLOW":true}}""")
            .expectOk()
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(true))
            .andExpect(jsonPath("$.data.categories.BUNDLE_NEWS").value(false))
            .andExpect(jsonPath("$.data.categories.REACTION").value(false))
    }

    @Test
    fun `sending the same patch again gives the same result`() {
        val access = access(member())
        val body = """{"categories":{"MEMBER_ROLE":false}}"""

        val first = change(access, body).expectOk().andReturn().response.contentAsString
        val second = change(access, body).expectOk().andReturn().response.contentAsString

        assertThat(second).isEqualTo(first)
        assertThat(count("push_preferences")).isEqualTo(1)
    }

    @Test
    fun `an empty patch changes nothing`() {
        val access = access(member())
        change(access, """{"categories":{"FOLLOW":false}}""").expectOk()

        change(access, "{}")
            .expectOk()
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.categories.FOLLOW").value(false))
            .andExpect(jsonPath("$.data.categories.REACTION").value(true))
    }

    @Test
    fun `the former per type field is ignored`() {
        val access = access(member())

        change(access, """{"types":{"MEMBER_JOINED":false}}""")
            .expectOk()
            .andExpect(jsonPath("$.data.categories.MEMBER_ROLE").value(true))
            .andExpect(jsonPath("$.data.types").doesNotExist())
    }

    @Test
    fun `settings belong to the account and every session of it sees them`() {
        val memberId = member()
        val first = access(memberId)
        val second = access(memberId)

        change(first, """{"categories":{"REACTION":false}}""").expectOk()

        read(second)
            .expectOk()
            .andExpect(jsonPath("$.data.categories.REACTION").value(false))
        assertThat(count("push_preferences")).isEqualTo(1)
    }

    @Test
    fun `the saved row keeps all four categories and leaves the former type column alone`() {
        val memberId = member()
        change(access(memberId), """{"categories":{"FOLLOW":false}}""").expectOk()

        val row = jdbc.queryForMap(
            "SELECT enabled, categories::text AS categories, types::text AS types " +
                "FROM push_preferences WHERE member_id = ?",
            memberId
        )

        assertThat(row["enabled"]).isEqualTo(true)
        assertThat(row["types"]).isEqualTo("{}")
        assertThat(row["categories"].toString())
            .contains("\"FOLLOW\": false", "\"BUNDLE_NEWS\": true", "\"MEMBER_ROLE\": true", "\"REACTION\": true")
    }

    @Test
    fun `an unfinished signup and a request without a token are refused`() {
        val access = access(member(completed = false))

        read(access).expectError(ErrorCode.SIGNUP_INCOMPLETE)
        change(access, """{"enabled":false}""").expectError(ErrorCode.SIGNUP_INCOMPLETE)
        mvc.perform(get("/api/v1/members/me/push-preferences"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.UNAUTHORIZED.code))
        assertThat(count("push_preferences")).isZero()
    }

    private fun read(access: String): ResultActions = mvc.perform(
        get("/api/v1/members/me/push-preferences").header(HttpHeaders.AUTHORIZATION, "Bearer $access")
    )

    private fun change(access: String, body: String): ResultActions = mvc.perform(
        patch("/api/v1/members/me/push-preferences")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $access")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    )

    private fun access(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return pair.accessToken
    }

    private fun ResultActions.expectOk(): ResultActions = andExpect(status().isOk)
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.error").isEmpty)

    private fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(error.code))
}
