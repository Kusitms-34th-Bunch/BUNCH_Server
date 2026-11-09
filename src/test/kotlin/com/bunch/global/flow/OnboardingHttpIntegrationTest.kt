package com.bunch.global.flow

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.member.domain.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val members: MemberRepository,
    private val tokens: TokenProvider,
    private val sessions: RefreshTokenRepository,
    private val consents: MemberConsentRepository,
    private val jdbc: JdbcTemplate
) {
    private val ids = mutableListOf<Long>()
    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanup() {
        sessionIds.forEach { (sid, id) -> sessions.delete(sid, id) }
        ids.forEach { jdbc.update("DELETE FROM bunches WHERE created_by = ?", it) }
        ids.forEach { members.deleteById(it) }
    }

    private fun account(): Pair<Long, String> {
        val member = members.saveAndFlush(Member())
        ids += member.id
        val pair = tokens.generateToken(member.id, member.role.name)
        val sid = tokens.getSessionId(pair.accessToken)
        sessions.save(sid, member.id, pair.refreshToken, 3600)
        sessionIds += sid to member.id
        return member.id to pair.accessToken
    }

    private fun register(access: String, nickname: String) {
        val agreements = TermType.entries.filter { it.required }.map { mapOf("type" to it, "version" to "v1", "agreed" to true) }
        mvc.perform(post("/api/v1/auth/terms/agreements").header("Authorization", "Bearer $access")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(mapOf("agreements" to agreements))))
            .andExpect(status().isOk)
        val profile = MockMultipartFile("profile", "", "application/json", mapper.writeValueAsBytes(mapOf("nickname" to nickname)))
        mvc.perform(multipart("/api/v1/members/me/registration").file(profile).header("Authorization", "Bearer $access"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.signupStatus").value("COMPLETED"))
    }

    @Test
    fun `optional terms are saved idempotently and reused during registration`() {
        val (id, access) = account()
        val agreements = TermType.entries.map {
            mapOf("type" to it, "version" to "v1", "agreed" to true)
        }
        fun submit() {
            mvc.perform(
                post("/api/v1/auth/terms/agreements")
                    .header("Authorization", "Bearer $access")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(mapOf("agreements" to agreements)))
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
        }

        submit()
        val before = consents.findAllByMemberId(id).associate { it.type to (it.id to it.agreedAt) }
        assertThat(before.keys).containsExactlyInAnyOrderElementsOf(TermType.entries)
        submit()
        assertThat(consents.findAllByMemberId(id).associate { it.type to (it.id to it.agreedAt) })
            .isEqualTo(before)

        val profile = MockMultipartFile(
            "profile",
            "",
            "application/json",
            mapper.writeValueAsBytes(
                mapOf("nickname" to "선택동의", "birthYear" to 1990, "demographicsConsentVersion" to "v1")
            )
        )
        mvc.perform(
            multipart("/api/v1/members/me/registration")
                .file(profile)
                .header("Authorization", "Bearer $access")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.signupStatus").value("COMPLETED"))
        assertThat(members.findById(id).orElseThrow().birthYear).isEqualTo(1990)
        assertThat(consents.findAllByMemberId(id).associate { it.type to (it.id to it.agreedAt) })
            .isEqualTo(before)
    }

    @Test
    fun `optional terms do not bypass required consent or duplicate validation`() {
        val (id, access) = account()
        val required = TermType.entries.filter { it.required }.map {
            mapOf("type" to it, "version" to "v1", "agreed" to true)
        }
        val optional = mapOf("type" to TermType.DEMOGRAPHICS, "version" to "v1", "agreed" to true)
        val invalid = listOf(
            required.dropLast(1) + optional,
            required + required.first() + optional,
            required + optional + optional,
            required.map { it + ("agreed" to false) } + optional,
            required + (optional + ("agreed" to false))
        )
        invalid.forEach { agreements ->
            mvc.perform(
                post("/api/v1/auth/terms/agreements")
                    .header("Authorization", "Bearer $access")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(mapOf("agreements" to agreements)))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
            assertThat(consents.findAllByMemberId(id)).isEmpty()
        }
    }

    @Test
    fun `optional terms version mismatch rejects all consents atomically`() {
        val (id, access) = account()
        val agreements = TermType.entries.map {
            mapOf("type" to it, "version" to if (it.required) "v1" else "old", "agreed" to true)
        }
        mvc.perform(
            post("/api/v1/auth/terms/agreements")
                .header("Authorization", "Bearer $access")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(mapOf("agreements" to agreements)))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("AUTH_016"))
        assertThat(consents.findAllByMemberId(id)).isEmpty()
    }

    @Test
    fun `pending signup resumes then creates invites and joins through authenticated APIs`() {
        val (ownerId, ownerAccess) = account()
        mvc.perform(get("/api/v1/members/me/onboarding").header("Authorization", "Bearer $ownerAccess"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.memberId").value(ownerId))
            .andExpect(jsonPath("$.data.signupStatus").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.termsAccepted").value(false))
            .andExpect(jsonPath("$.data.defaultBunchId").isEmpty)
        val createBody = """{"name":"HTTP 번치","introduction":"함께\n기록해요 👨‍👩‍👧‍👦","visibility":"PRIVATE","characterType":"TYPE_2"}"""
        val key = UUID.randomUUID().toString()
        mvc.perform(post("/api/v1/bunches").header("Authorization", "Bearer $ownerAccess")
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("AUTH_015"))
        mvc.perform(get("/api/v1/auth/terms").header("Authorization", "Bearer $ownerAccess"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.terms.length()").value(4))
        val agreements = TermType.entries.filter { it.required }
            .map { mapOf("type" to it, "version" to "v1", "agreed" to true) }
        mvc.perform(post("/api/v1/auth/terms/agreements").header("Authorization", "Bearer $ownerAccess")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(mapOf("agreements" to agreements))))
            .andExpect(status().isOk)
        mvc.perform(get("/api/v1/members/me/onboarding").header("Authorization", "Bearer $ownerAccess"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.memberId").value(ownerId))
            .andExpect(jsonPath("$.data.signupStatus").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.termsAccepted").value(true))
            .andExpect(jsonPath("$.data.defaultBunchId").isEmpty)
        register(ownerAccess, "HTTP 생성자")
        mvc.perform(get("/api/v1/members/me/onboarding").header("Authorization", "Bearer $ownerAccess"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.signupStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.data.termsAccepted").value(true))
            .andExpect(jsonPath("$.data.defaultBunchId").isEmpty)
        val created = mvc.perform(post("/api/v1/bunches").header("Authorization", "Bearer $ownerAccess")
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.characterType").value("TYPE_2"))
            .andReturn().response.contentAsString
        val bunchId = mapper.readTree(created).path("data").path("id").asLong()
        val invitation = mvc.perform(post("/api/v1/bunches/$bunchId/invitations").header("Authorization", "Bearer $ownerAccess"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val code = mapper.readTree(invitation).path("data").path("code").asText()
        val url = mapper.readTree(invitation).path("data").path("url").asText()
        val token = url.substringAfterLast('/')
        mvc.perform(get("/api/v1/invitations/$token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.name").value("HTTP 번치"))
            .andExpect(jsonPath("$.data.code").value(code))
            .andExpect(jsonPath("$.data.memberCount").doesNotExist())
        val (_, joiningAccess) = account()
        register(joiningAccess, "HTTP 참여자")
        mvc.perform(post("/api/v1/invitations/verify").header("Authorization", "Bearer $joiningAccess")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(mapOf("invitation" to code.lowercase()))))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.eligibility").value("AVAILABLE"))
        val joinKey = UUID.randomUUID().toString()
        repeat(2) {
            mvc.perform(post("/api/v1/invitations/join").header("Authorization", "Bearer $joiningAccess")
                .header("Idempotency-Key", joinKey).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(mapOf("invitation" to if (it == 0) code else url))))
                .andExpect(status().isOk).andExpect(jsonPath("$.data.outcome").value("JOINED"))
                .andExpect(jsonPath("$.data.bunch.id").value(bunchId))
        }
        mvc.perform(get("/api/v1/members/me/onboarding").header("Authorization", "Bearer $joiningAccess"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.defaultBunchId").value(bunchId))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bunch_memberships WHERE bunch_id = ?", Int::class.java, bunchId)).isEqualTo(2)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE bunch_id = ? AND type = 'MEMBER_JOINED'",
                Int::class.java,
                bunchId
            )
        ).isEqualTo(1)
    }

    @Test
    fun `invalid input and withdrawn account use common errors without exposing data`() {
        val (id, access) = account()
        mvc.perform(get("/api/v1/members/nickname-availability").header("Authorization", "Bearer $access"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error.code").value("COMMON_001"))
        val invalid = MockMultipartFile("profile", "", "application/json", """{"nickname":"이름","gender":"OTHER"}""".toByteArray())
        mvc.perform(multipart("/api/v1/members/me/registration").file(invalid).header("Authorization", "Bearer $access"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error.code").value("COMMON_002"))
        val member = members.findById(id).get()
        member.accountStatus = AccountStatus.WITHDRAWN
        members.saveAndFlush(member)
        val sessionId = tokens.getSessionId(access)
        mvc.perform(get("/api/v1/members/me/onboarding").header("Authorization", "Bearer $access"))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.data").isEmpty)
        assertThat(sessions.isActive(sessionId, id)).isFalse()
    }
}
