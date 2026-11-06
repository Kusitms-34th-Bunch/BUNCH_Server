package com.bunch.place

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PinnedPlaceHttpIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var mapper: ObjectMapper

    private val activeSessions = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanPins() {
        activeSessions.forEach { (sessionId, member) -> sessions.delete(sessionId, member) }
        activeSessions.clear()
        jdbc.update("DELETE FROM pinned_places")
        jdbc.update("DELETE FROM bundles")
    }

    @Test
    fun `HTTP stores only actual bundle location ignoring forged client snapshot and current bunch`() {
        val f = fixture()
        val authorization = bearer(f.actor)
        val body = """{"provider":"forged","externalId":"fake","name":"forged name",
            "address":"forged address","bunchId":${f.otherBunch}}"""
        val result = mvc.perform(put(path(f)).header(HttpHeaders.AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.provider").value("kakao"))
            .andExpect(jsonPath("$.data.externalId").value("external"))
            .andExpect(jsonPath("$.data.name").value("real name"))
            .andExpect(jsonPath("$.data.address").value("real address"))
            .andExpect(jsonPath("$.data.bunchId").value(f.bunch))
            .andExpect(jsonPath("$.data.sourceType").value("BUNDLE"))
            .andExpect(jsonPath("$.data.sourceLocationId").value(f.location))
            .andExpect(jsonPath("$.data.pinnedByMemberId").value(f.actor))
            .andReturn()
        val pin = mapper.readTree(result.response.contentAsByteArray).path("data").path("id").asLong()
        mvc.perform(put(path(f)).header(HttpHeaders.AUTHORIZATION, authorization))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.id").value(pin))
        mvc.perform(get("/api/v1/bundles/${f.bundle}/pinned-places")
            .header(HttpHeaders.AUTHORIZATION, authorization))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.canPin").value(true))
            .andExpect(jsonPath("$.data.currentBunchId").value(f.bunch))
            .andExpect(jsonPath("$.data.locations[0].pinned").value(true))
            .andExpect(jsonPath("$.data.locations[0].pinId").value(pin))
        val colleague = member()
        join(colleague, issue(f.actor, f.bunch))
        repeat(2) {
            mvc.perform(delete("/api/v1/pinned-places/$pin").header(HttpHeaders.AUTHORIZATION, bearer(colleague)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
        }
        assertThat(count("pinned_places")).isZero()
    }

    @Test
    fun `HTTP requires active JWT session on every action`() {
        val f = fixture()
        mvc.perform(put(path(f))).expectError(ErrorCode.UNAUTHORIZED)
        mvc.perform(get("/api/v1/bundles/${f.bundle}/pinned-places")).expectError(ErrorCode.UNAUTHORIZED)
        mvc.perform(delete("/api/v1/pinned-places/1")).expectError(ErrorCode.UNAUTHORIZED)
        val session = UUID.randomUUID().toString()
        val token = tokens.createAccessToken(f.actor, "ROLE_USER", session)
        mvc.perform(put(path(f)).header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .expectError(ErrorCode.UNAUTHORIZED)
        assertThat(count("pinned_places")).isZero()
    }

    @Test
    fun `HTTP zero memberships can read public sheet but cannot pin or cancel`() {
        val f = fixture()
        val visitor = bearer(member())
        mvc.perform(get("/api/v1/bundles/${f.bundle}/pinned-places").header(HttpHeaders.AUTHORIZATION, visitor))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.canPin").value(false))
            .andExpect(jsonPath("$.data.currentBunchId").doesNotExist())
            .andExpect(jsonPath("$.data.locations[0].pinned").value(false))
        mvc.perform(put(path(f)).header(HttpHeaders.AUTHORIZATION, visitor))
            .expectError(ErrorCode.CURRENT_BUNCH_REQUIRED)
        mvc.perform(delete("/api/v1/pinned-places/1").header(HttpHeaders.AUTHORIZATION, visitor))
            .expectError(ErrorCode.CURRENT_BUNCH_REQUIRED)
    }

    @Test
    fun `HTTP rejects nonpositive ids missing locations and foreign bundle location membership`() {
        val f = fixture()
        val authorization = bearer(f.actor)
        mvc.perform(put("/api/v1/bundles/0/locations/${f.location}/pin")
            .header(HttpHeaders.AUTHORIZATION, authorization)).expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(put("/api/v1/bundles/${f.bundle}/locations/-1/pin")
            .header(HttpHeaders.AUTHORIZATION, authorization)).expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(put("/api/v1/bundles/${f.bundle}/locations/${Long.MAX_VALUE}/pin")
            .header(HttpHeaders.AUTHORIZATION, authorization)).expectError(ErrorCode.RESOURCE_NOT_FOUND)
        val another = bundle(f.otherBunch, f.actor)
        mvc.perform(put("/api/v1/bundles/$another/locations/${f.location}/pin")
            .header(HttpHeaders.AUTHORIZATION, authorization)).expectError(ErrorCode.RESOURCE_NOT_FOUND)
        mvc.perform(delete("/api/v1/pinned-places/0").header(HttpHeaders.AUTHORIZATION, authorization))
            .expectError(ErrorCode.INVALID_INPUT)
        assertThat(count("pinned_places")).isZero()
    }

    @Test
    fun `HTTP rechecks membership and published source at request time`() {
        val f = fixture()
        val authorization = bearer(f.actor)
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", f.bundle)
        mvc.perform(put(path(f)).header(HttpHeaders.AUTHORIZATION, authorization))
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)
        jdbc.update("UPDATE bundles SET visibility = 'PUBLIC' WHERE id = ?", f.bundle)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", f.actor)
        mvc.perform(put(path(f)).header(HttpHeaders.AUTHORIZATION, authorization))
            .expectError(ErrorCode.CURRENT_BUNCH_REQUIRED)
        assertThat(count("pinned_places")).isZero()
    }

    private fun fixture(): Fixture {
        val actor = member()
        val bunch = create(actor).id
        val owner = member()
        val source = create(owner).id
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", source)
        val bundle = bundle(source, owner)
        val location = jdbc.queryForObject(
            """INSERT INTO bundle_locations(bundle_id, provider, external_id, name, address, position)
                VALUES (?, 'kakao', 'external', 'real name', 'real address', 0) RETURNING id""",
            Long::class.java, bundle
        )!!
        return Fixture(actor, bunch, source, bundle, location)
    }

    private fun bundle(bunch: Long, owner: Long): Long = jdbc.queryForObject(
        """INSERT INTO bundles(bunch_id, created_by, status, title, visibility, created_at, updated_at, published_at)
            VALUES (?, ?, 'PUBLISHED', 'published', 'PUBLIC', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id""", Long::class.java, bunch, owner
    )!!

    private fun bearer(member: Long): String {
        val session = UUID.randomUUID().toString()
        sessions.save(session, member, "test-refresh", 3600)
        activeSessions.add(session to member)
        return "Bearer ${tokens.createAccessToken(member, "ROLE_USER", session)}"
    }

    private fun path(f: Fixture) = "/api/v1/bundles/${f.bundle}/locations/${f.location}/pin"

    private fun ResultActions.expectError(code: ErrorCode) =
        andExpect(status().`is`(code.status)).andExpect(jsonPath("$.error.code").value(code.code))

    private data class Fixture(
        val actor: Long,
        val bunch: Long,
        val otherBunch: Long,
        val bundle: Long,
        val location: Long
    )
}
