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
        val (member, access) = account(birthYear = 1990, gender = Gender.MALE)
        val nickname = "수정${UUID.randomUUID().toString().take(6)}"
        val uploaded = StoredImage("profiles/http-${UUID.randomUUID()}", "https://res.cloudinary.com/test/http.jpg")
            .also { publicIds += it.publicId }
        val photo = MockMultipartFile("photo", "p.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        `when`(images.upload(photo)).thenReturn(uploaded)

        put(access, UUID.randomUUID().toString(), body(nickname, 1999, "FEMALE", "REPLACE"), photo)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.memberId").value(member.id))
            .andExpect(jsonPath("$.data.nickname").value(nickname))
            .andExpect(jsonPath("$.data.birthYear").value(1999))
            .andExpect(jsonPath("$.data.gender").value("FEMALE"))
            .andExpect(jsonPath("$.data.imageUrl").value(uploaded.url))
            .andExpect(jsonPath("$.error").isEmpty)
        assertThat(assets.findAll().map { it.publicId }).contains(uploaded.publicId)
    }

    @Test
    fun `null birth year and gender are cleared`() {
        val (_, access) = account(birthYear = 1990, gender = Gender.MALE)

        put(access, UUID.randomUUID().toString(), """{"nickname":"비움${hex()}","imageAction":"KEEP"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.birthYear").isEmpty)
            .andExpect(jsonPath("$.data.gender").isEmpty)
    }

    @Test
    fun `request without a token is unauthorized`() {
        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/members/me/profile")
                .file(profilePart(body("무토큰${hex()}", null, null, "KEEP")))
                .header("Idempotency-Key", UUID.randomUUID().toString())
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
    }

    @Test
    fun `missing key or profile part and a bad key are invalid input`() {
        val (_, access) = account()

        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/members/me/profile")
                .file(profilePart(body("키없음${hex()}", null, null, "KEEP")))
                .header("Authorization", "Bearer $access")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_001"))

        mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/members/me/profile")
                .header("Authorization", "Bearer $access")
                .header("Idempotency-Key", UUID.randomUUID().toString())
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_001"))

        put(access, "not-a-uuid", body("키오류${hex()}", null, null, "KEEP"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_001"))
    }

    @Test
    fun `malformed profile json and an unknown image action are rejected`() {
        val (_, access) = account()

        put(access, UUID.randomUUID().toString(), "{")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_002"))
        put(access, UUID.randomUUID().toString(), body("액션${hex()}", null, null, "DELETE"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_002"))
    }

    @Test
    fun `duplicate nickname is a conflict and a stale key with other content conflicts`() {
        val (other, _) = account()
        val (_, access) = account()

        put(access, UUID.randomUUID().toString(), body(other.nickname!!, null, null, "KEEP"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("USER_002"))

        val key = UUID.randomUUID().toString()
        put(access, key, body("첫번째${hex()}", null, null, "KEEP")).andExpect(status().isOk)
        put(access, key, body("두번째${hex()}", null, null, "KEEP"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("REQUEST_001"))
    }

    @Test
    fun `unfinished signup is forbidden`() {
        val (_, access) = account(signup = SignupStatus.IN_PROGRESS)

        put(access, UUID.randomUUID().toString(), body("미완료${hex()}", null, null, "KEEP"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("AUTH_015"))
    }

    private fun put(
        access: String,
        key: String,
        profile: String,
        photo: MockMultipartFile? = null
    ): ResultActions {
        val request = multipart(HttpMethod.PUT, "/api/v1/members/me/profile")
            .file(profilePart(profile))
        photo?.let { request.file(it) }
        return mvc.perform(
            request
                .header("Authorization", "Bearer $access")
                .header("Idempotency-Key", key)
        )
    }

    private fun profilePart(json: String) =
        MockMultipartFile("profile", "", "application/json", json.toByteArray())

    private fun body(nickname: String, birthYear: Int?, gender: String?, action: String): String =
        """{"nickname":"$nickname","birthYear":${birthYear ?: "null"},""" +
            """"gender":${gender?.let { "\"$it\"" } ?: "null"},"imageAction":"$action"}"""

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
