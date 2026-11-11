package com.bunch.member

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.image.PhotoImageStorage
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class MemberWithdrawalHttpIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @MockitoBean
    private lateinit var profileImages: ProfileImageStorage

    @MockitoBean
    private lateinit var photoImages: PhotoImageStorage

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanWithdrawalHttpFixtures() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
        jdbc.update("DELETE FROM push_jobs")
        jdbc.update("DELETE FROM push_devices")
        jdbc.update("DELETE FROM push_preferences")
        jdbc.update("DELETE FROM bunch_access_notices")
        jdbc.update("DELETE FROM bunch_action_requests")
    }

    @Test
    fun `the check lists only bunches where the member is the main manager and others are present`() {
        val self = member()
        val guest = member()
        val handover = create(self)
        join(guest, issue(self, handover.id))
        create(self)
        val coManaged = create(guest)
        join(self, issue(guest, coManaged.id))
        jdbc.update(
            "UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE bunch_id = ? AND member_id = ?",
            coManaged.id,
            self
        )

        read(access(self), "/api/v1/members/me/withdrawal/check")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.ownerBunches.length()").value(1))
            .andExpect(jsonPath("$.data.ownerBunches[0].bunchId").value(handover.id))
            .andExpect(jsonPath("$.data.ownerBunches[0].name").value(handover.name))
            .andExpect(jsonPath("$.data.ownerBunches[0].myMembershipId").isNumber)
            .andExpect(jsonPath("$.data.ownerBunches[0].membershipVersion").isNumber)
    }

    @Test
    fun `the check is empty when nothing has to be handed over`() {
        val self = member()
        create(self)

        read(access(self), "/api/v1/members/me/withdrawal/check")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.ownerBunches.length()").value(0))
    }

    @Test
    fun `withdrawing answers with no data and the same token is refused afterwards`() {
        val self = member()
        val token = access(self)

        send(token, "/api/v1/members/me/withdrawal")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isEmpty)
            .andExpect(jsonPath("$.error").isEmpty)

        read(token, "/api/v1/members/me").andExpect(status().isUnauthorized)
        send(token, "/api/v1/members/me/withdrawal")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
    }

    @Test
    fun `a bunch that still needs a main manager stops the withdrawal with the bunch list`() {
        val self = member()
        val guest = member()
        val handover = create(self)
        join(guest, issue(self, handover.id))
        val token = access(self)

        send(token, "/api/v1/members/me/withdrawal")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("USER_005"))
            .andExpect(jsonPath("$.error.detail.bunches.length()").value(1))
            .andExpect(jsonPath("$.error.detail.bunches[0].bunchId").value(handover.id))
            .andExpect(jsonPath("$.error.detail.bunches[0].name").value(handover.name))

        read(token, "/api/v1/members/me").andExpect(status().isOk)
    }

    @Test
    fun `requests without a token and from an unfinished signup are refused`() {
        mvc.perform(post("/api/v1/members/me/withdrawal"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
        mvc.perform(get("/api/v1/members/me/withdrawal/check"))
            .andExpect(status().isUnauthorized)

        val token = access(member(completed = false))
        send(token, "/api/v1/members/me/withdrawal")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("AUTH_015"))
        read(token, "/api/v1/members/me/withdrawal/check")
            .andExpect(status().isForbidden)
    }

    @Test
    fun `a withdrawn account is refused by the profile and push settings APIs`() {
        val self = member()
        send(access(self), "/api/v1/members/me/withdrawal").andExpect(status().isOk)
        val later = access(self)

        val profile = MockMultipartFile(
            "profile",
            "",
            "application/json",
            """{"nickname":"새이름","imageAction":"KEEP"}""".toByteArray()
        )
        mvc.perform(
            multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/members/me/profile")
                .file(profile)
                .header(HttpHeaders.AUTHORIZATION, later)
        ).andExpect(status().isUnauthorized)
        mvc.perform(
            patch("/api/v1/members/me/push-preferences")
                .header(HttpHeaders.AUTHORIZATION, later)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":false}""")
        ).andExpect(status().isUnauthorized)
        read(later, "/api/v1/members/me/withdrawal/check").andExpect(status().isUnauthorized)
    }

    private fun read(token: String, path: String): ResultActions =
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, token))

    private fun send(token: String, path: String): ResultActions =
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, token))

    private fun access(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return "Bearer ${pair.accessToken}"
    }
}
