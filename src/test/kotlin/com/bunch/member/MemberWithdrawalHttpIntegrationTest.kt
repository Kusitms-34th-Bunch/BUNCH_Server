package com.bunch.member

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.PhotoImageStorage
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.oidc.AppleTokenRevoker
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
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

    @MockitoBean
    private lateinit var apple: AppleTokenRevoker

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanWithdrawalHttpFixtures() {
        jdbc.update("DELETE FROM external_identity WHERE provider_subject LIKE 'withdrawal-http-%'")
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

    @Test
    fun `the check tells the provider the member signed in with`() {
        val apple = appleMember("withdrawal-http-check")
        val kakao = member().also { identity(it, "KAKAO", "withdrawal-http-kakao-$it") }
        val none = member()

        read(access(apple.first), "/api/v1/members/me/withdrawal/check")
            .andExpect(jsonPath("$.data.provider").value("APPLE"))
        read(access(kakao), "/api/v1/members/me/withdrawal/check")
            .andExpect(jsonPath("$.data.provider").value("KAKAO"))
        read(access(none), "/api/v1/members/me/withdrawal/check")
            .andExpect(jsonPath("$.data.provider").isEmpty)
    }

    @Test
    fun `an Apple member withdraws with the code, which is used to cancel the Apple connection`() {
        val (self, subject) = appleMember("withdrawal-http-ok")
        val token = access(self)

        sendWithCode(token, "code-1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        Mockito.verify(apple).revoke("code-1", subject)
        read(token, "/api/v1/members/me").andExpect(status().isUnauthorized)
        assertThat(count("external_identity WHERE member_id = $self")).isZero()
    }

    @Test
    fun `an Apple member without a code is refused and keeps the account`() {
        val (self) = appleMember("withdrawal-http-nocode")
        val token = access(self)

        send(token, "/api/v1/members/me/withdrawal")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("USER_006"))
        sendWithCode(token, "   ")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("USER_006"))

        Mockito.verifyNoInteractions(apple)
        read(token, "/api/v1/members/me").andExpect(status().isOk)
        assertThat(count("external_identity WHERE member_id = $self")).isEqualTo(1)
    }

    @Test
    fun `when Apple cannot cancel the connection the withdrawal is stopped and the account stays`() {
        val (self, subject) = appleMember("withdrawal-http-fail")
        val token = access(self)
        Mockito.doThrow(BusinessException(ErrorCode.APPLE_REVOKE_FAILED)).`when`(apple).revoke("code-1", subject)

        sendWithCode(token, "code-1")
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("USER_007"))

        read(token, "/api/v1/members/me").andExpect(status().isOk)
        assertThat(count("external_identity WHERE member_id = $self")).isEqualTo(1)
    }

    @Test
    fun `an Apple member who still has to hand over a bunch is stopped before Apple is called`() {
        val (self) = appleMember("withdrawal-http-owner")
        val guest = member()
        val handover = create(self)
        join(guest, issue(self, handover.id))

        sendWithCode(access(self), "code-1")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("USER_005"))

        Mockito.verifyNoInteractions(apple)
    }

    @Test
    fun `a member who did not sign in with Apple withdraws as before and Apple is not called`() {
        val self = member()
        identity(self, "KAKAO", "withdrawal-http-kakao-$self")

        sendWithCode(access(self), "code-ignored").andExpect(status().isOk)

        Mockito.verifyNoInteractions(apple)
        assertThat(count("external_identity WHERE member_id = $self")).isZero()
    }

    private fun appleMember(prefix: String): Pair<Long, String> {
        val id = member()
        val subject = "$prefix-$id"
        identity(id, "APPLE", subject)
        return id to subject
    }

    private fun identity(memberId: Long, provider: String, subject: String) {
        jdbc.update(
            "INSERT INTO external_identity (member_id, provider, provider_subject) VALUES (?, ?, ?)",
            memberId,
            provider,
            subject
        )
    }

    private fun sendWithCode(token: String, code: String): ResultActions = mvc.perform(
        post("/api/v1/members/me/withdrawal")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"appleAuthorizationCode":"$code"}""")
    )

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
