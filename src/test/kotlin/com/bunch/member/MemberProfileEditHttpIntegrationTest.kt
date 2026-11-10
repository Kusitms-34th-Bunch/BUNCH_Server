package com.bunch.member

import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.member.domain.Gender
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class MemberProfileEditHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val members: MemberRepository,
    private val tokens: TokenProvider,
    private val sessions: RefreshTokenRepository,
    private val assets: ImageAssetRepository,
    private val jdbc: JdbcTemplate
) {
    @MockitoBean
    lateinit var images: ProfileImageStorage

    private val memberIds = mutableListOf<Long>()
    private val sessionIds = mutableListOf<Pair<String, Long>>()
    private val publicIds = mutableListOf<String>()

    @AfterEach
    fun cleanup() {
        sessionIds.forEach { (sid, id) -> sessions.delete(sid, id) }
        memberIds.forEach { members.deleteById(it) }
        publicIds.forEach { jdbc.update("DELETE FROM image_assets WHERE public_id = ?", it) }
    }

    @Test
    fun `edit returns the same member shape as the profile lookup`() {
        val (member, access) = account()
        val nickname = "수정${UUID.randomUUID().toString().take(6)}"
        val uploaded = StoredImage("profiles/http-${UUID.randomUUID()}", "https://res.cloudinary.com/test/http.jpg")
            .also { publicIds += it.publicId }
        val photo = MockMultipartFile("photo", "p.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        `when`(images.upload(photo)).thenReturn(uploaded)

        put(access, body(nickname, "REPLACE"), photo)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.memberId").value(member.id))
            .andExpect(jsonPath("$.data.nickname").value(nickname))
            .andExpect(jsonPath("$.data.imageUrl").value(uploaded.url))
            .andExpect(jsonPath("$.error").isEmpty)
        assertThat(assets.findAll().map { it.publicId }).contains(uploaded.publicId)
    }

    @Test
    fun `birth year and gender are ignored in the request and not returned`() {
        val (member, access) = account(birthYear = 1990, gender = Gender.MALE)

        put(access, """{"nickname":"무시${hex()}","birthYear":2000,"gender":"FEMALE","imageAction":"KEEP"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.birthYear").doesNotExist())
            .andExpect(jsonPath("$.data.gender").doesNotExist())
        val saved = members.findById(member.id).get()
        assertThat(saved.birthYear).isEqualTo(1990)
        assertThat(saved.gender).isEqualTo(Gender.MALE)
    }

    @Test
    fun `request without a token is unauthorized`() {
        mvc.perform(profileRequest(body("무토큰${hex()}", "KEEP")))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
    }

    @Test
    fun `a missing profile part is invalid input`() {
        val (_, access) = account()

        put(access, null)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_001"))
    }

    @Test
    fun `a request key from an older app is ignored and the edit is applied`() {
        val (_, access) = account()
        val nickname = "키무시${hex()}"

        mvc.perform(
            profileRequest(body(nickname, "KEEP"))
                .header("Authorization", "Bearer $access")
                .header("Idempotency-Key", UUID.randomUUID().toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value(nickname))
    }

    @Test
    fun `malformed profile json and an unknown image action are rejected`() {
        val (_, access) = account()

        put(access, "{")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_002"))
        put(access, body("액션${hex()}", "DELETE"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_002"))
    }

    @Test
    fun `duplicate nickname is a conflict`() {
        val (other, _) = account()
        val (_, access) = account()

        put(access, body(other.nickname!!, "KEEP"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("USER_002"))
    }

    @Test
    fun `unfinished signup is forbidden`() {
        val (_, access) = account(signup = SignupStatus.IN_PROGRESS)

        put(access, body("미완료${hex()}", "KEEP"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("AUTH_015"))
    }

    private fun put(
        access: String,
        profile: String?,
        photo: MockMultipartFile? = null
    ): ResultActions = mvc.perform(profileRequest(profile, photo).header("Authorization", "Bearer $access"))

    // A null profile leaves the profile part out of the request.
    private fun profileRequest(
        profile: String?,
        photo: MockMultipartFile? = null
    ): MockMultipartHttpServletRequestBuilder {
        val request = multipart(HttpMethod.PUT, "/api/v1/members/me/profile")
        profile?.let { request.file(MockMultipartFile("profile", "", "application/json", it.toByteArray())) }
        photo?.let { request.file(it) }
        return request
    }

    private fun body(nickname: String, action: String): String = """{"nickname":"$nickname","imageAction":"$action"}"""

    private fun account(
        birthYear: Int? = null,
        gender: Gender? = null,
        signup: SignupStatus = SignupStatus.COMPLETED
    ): Pair<Member, String> {
        val member = members.saveAndFlush(
            Member(
                signupStatus = signup,
                nickname = "회원${hex()}",
                birthYear = birthYear,
                gender = gender
            )
        ).also { memberIds += it.id }
        val pair = tokens.generateToken(member.id, member.role.name)
        val sid = tokens.getSessionId(pair.accessToken)
        sessions.save(sid, member.id, pair.refreshToken, 3600)
        sessionIds += sid to member.id
        return member to pair.accessToken
    }

    private fun hex(): String = UUID.randomUUID().toString().replace("-", "").take(8)
}
