package com.bunch.bunch

import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenPair
import com.bunch.global.security.TokenProvider
import com.fasterxml.jackson.databind.JsonNode
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
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BunchSocialHttpIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanSocialSessions() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    @Test
    fun `public follow is immediate and relation DTOs omit private fields`() {
        val follower = member()
        val followerBunch = create(follower).id
        val targetOwner = member()
        val target = create(targetOwner).id
        makePublic(target)
        val access = bearer(follower)

        val following = mvc.perform(
            put(followingPath(followerBunch, target))
                .header(HttpHeaders.AUTHORIZATION, access)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.followState").value("FOLLOWING"))
            .andReturn()
        val followId = following.data().path("followId").asLong()
        assertThat(followId).isGreaterThan(0L)

        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("viewerBunchId", followerBunch.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].bunch.id").value(followerBunch))
            .andExpect(jsonPath("$.data.items[0].followState").value("NONE"))
            .andExpect(jsonPath("$.data.items[0].followId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].bunch.visibility").value("PRIVATE"))
            .andExpect(jsonPath("$.data.items[0].bunch.introduction").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].bunch.createdBy").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].bunch.profileVersion").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].actions").doesNotExist())

        mvc.perform(
            get(followingPath(followerBunch))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("viewerBunchId", followerBunch.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].bunch.id").value(target))
            .andExpect(jsonPath("$.data.items[0].followState").value("FOLLOWING"))
            .andExpect(jsonPath("$.data.items[0].followId").value(followId))
    }

    @Test
    fun `private follow request is listed with the common page shape and can be approved`() {
        val follower = member()
        val followerBunch = create(follower).id
        val targetOwner = member()
        val target = create(targetOwner).id
        val targetMember = member()
        join(targetMember, issue(targetOwner, target))
        val followerAccess = bearer(follower)

        val pending = mvc.perform(
            put(followingPath(followerBunch, target))
                .header(HttpHeaders.AUTHORIZATION, followerAccess)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.followState").value("REQUESTED"))
            .andReturn()
        val followId = pending.data().path("followId").asLong()
        assertThat(followId).isGreaterThan(0L)

        val targetMemberAccess = bearer(targetMember)
        val requests = mvc.perform(
            get(followRequestsPath(target))
                .header(HttpHeaders.AUTHORIZATION, targetMemberAccess)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.items[0].id").value(followId))
            .andExpect(jsonPath("$.data.items[0].bunch.id").value(followerBunch))
            .andExpect(jsonPath("$.data.items[0].bunch.visibility").value("PRIVATE"))
            .andExpect(jsonPath("$.data.items[0].bunch.introduction").doesNotExist())
            .andReturn()
        val requestId = requests.data().path("items").get(0).path("id").asLong()

        mvc.perform(
            post(decisionPath(target, requestId))
                .header(HttpHeaders.AUTHORIZATION, targetMemberAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"decision":"APPROVE"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requestId").value(requestId))
            .andExpect(jsonPath("$.data.applied").value(true))
            .andExpect(jsonPath("$.data.state").value("APPROVED"))

        mvc.perform(
            get(followingPath(followerBunch))
                .header(HttpHeaders.AUTHORIZATION, followerAccess)
                .param("viewerBunchId", followerBunch.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].bunch.id").value(target))
            .andExpect(jsonPath("$.data.items[0].followState").value("FOLLOWING"))
            .andExpect(jsonPath("$.data.items[0].followId").value(requestId))
    }

    @Test
    fun `private follow request can be rejected and an old decision cannot change a new request`() {
        val follower = member()
        val followerBunch = create(follower).id
        val targetOwner = member()
        val target = create(targetOwner).id
        val followerAccess = bearer(follower)
        val ownerAccess = bearer(targetOwner)

        val first = requestFollow(followerAccess, followerBunch, target)
        val firstId = first.data().path("followId").asLong()
        decide(target, firstId, ownerAccess, "REJECT")
            .andExpect(jsonPath("$.data.requestId").value(firstId))
            .andExpect(jsonPath("$.data.applied").value(true))
            .andExpect(jsonPath("$.data.state").value("REJECTED"))

        mvc.perform(
            get(followingPath(followerBunch))
                .header(HttpHeaders.AUTHORIZATION, followerAccess)
                .param("viewerBunchId", followerBunch.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isEmpty)

        val second = requestFollow(followerAccess, followerBunch, target)
        val secondId = second.data().path("followId").asLong()
        assertThat(secondId).isNotEqualTo(firstId)

        decide(target, firstId, ownerAccess, "APPROVE")
            .andExpect(jsonPath("$.data.requestId").value(firstId))
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.state").value("UNAVAILABLE"))

        mvc.perform(
            get(followRequestsPath(target))
                .header(HttpHeaders.AUTHORIZATION, ownerAccess)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(secondId))
    }

    @Test
    fun `followId is mandatory and a stale followId cannot cancel a newer request`() {
        val follower = member()
        val followerBunch = create(follower).id
        val targetOwner = member()
        val target = create(targetOwner).id
        val access = bearer(follower)

        val first = requestFollow(access, followerBunch, target)
        val firstId = first.data().path("followId").asLong()

        mvc.perform(
            delete(followingPath(followerBunch, target))
                .header(HttpHeaders.AUTHORIZATION, access)
        ).expectError(ErrorCode.INVALID_INPUT)

        mvc.perform(
            delete(followingPath(followerBunch, target))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("followId", firstId.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.followState").value("NONE"))
            .andExpect(jsonPath("$.data.cancelled").value("REQUESTED"))

        val second = requestFollow(access, followerBunch, target)
        val secondId = second.data().path("followId").asLong()
        assertThat(secondId).isNotEqualTo(firstId)

        mvc.perform(
            delete(followingPath(followerBunch, target))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("followId", firstId.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.followState").value("REQUESTED"))
            .andExpect(jsonPath("$.data.followId").value(secondId))
            .andExpect(jsonPath("$.data.cancelled").value("NONE"))
    }

    @Test
    fun `an active member can follow from the member bunch and from another own bunch`() {
        val memberOwner = member()
        val memberBunch = create(memberOwner).id
        val actor = member()
        join(actor, issue(memberOwner, memberBunch))
        val actorOwnedBunch = create(actor).id
        val targetOwner = member()
        val target = create(targetOwner).id
        makePublic(target)
        val access = bearer(actor)

        val fromMemberBunch = requestFollow(access, memberBunch, target)
            .andExpect(jsonPath("$.data.followState").value("FOLLOWING"))
            .andReturn()
        val firstId = fromMemberBunch.data().path("followId").asLong()

        val fromOwnedBunch = requestFollow(access, actorOwnedBunch, target)
            .andExpect(jsonPath("$.data.followState").value("FOLLOWING"))
            .andReturn()
        val secondId = fromOwnedBunch.data().path("followId").asLong()

        assertThat(firstId).isPositive()
        assertThat(secondId).isPositive()
        assertThat(secondId).isNotEqualTo(firstId)
    }

    @Test
    fun `private lists stay forbidden when the caller is a target member but chooses another viewer bunch`() {
        val targetOwner = member()
        val target = create(targetOwner).id
        val caller = member()
        join(caller, issue(targetOwner, target))
        val otherViewerBunch = create(caller).id
        val access = bearer(caller)

        val deniedRequests = listOf<() -> ResultActions>(
            {
                mvc.perform(
                    get(followersPath(target))
                        .header(HttpHeaders.AUTHORIZATION, access)
                        .param("viewerBunchId", otherViewerBunch.toString())
                        .param("page", "0")
                        .param("size", "20")
                )
            },
            {
                mvc.perform(
                    get(followingPath(target))
                        .header(HttpHeaders.AUTHORIZATION, access)
                        .param("viewerBunchId", otherViewerBunch.toString())
                        .param("page", "0")
                        .param("size", "20")
                )
            }
        )

        deniedRequests.forEach { request -> request().expectError(ErrorCode.FORBIDDEN) }
    }

    @Test
    fun `relation reads use server current and allow public reads without memberships`() {
        val targetOwner = member()
        val target = create(targetOwner).id
        makePublic(target)

        val noMembershipAccess = bearer(member())
        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, noMembershipAccess)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isEmpty)

        mvc.perform(
            get(followingPath(target))
                .header(HttpHeaders.AUTHORIZATION, noMembershipAccess)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isEmpty)

        val activeViewer = member()
        create(activeViewer)
        val activeAccess = bearer(activeViewer)
        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, activeAccess)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isEmpty)
    }

    @Test
    fun `a zero-membership user cannot perform a bunch-named social action`() {
        val unrelatedOwner = member()
        val unrelatedViewerBunch = create(unrelatedOwner).id
        val targetOwner = member()
        val target = create(targetOwner).id
        makePublic(target)
        val access = bearer(member())

        mvc.perform(
            put(followingPath(unrelatedViewerBunch, target))
                .header(HttpHeaders.AUTHORIZATION, access)
        ).expectError(ErrorCode.CURRENT_BUNCH_REQUIRED)
    }

    @Test
    fun `relation pages use the common shape and reject invalid page and size values`() {
        val follower = member()
        val followerBunch = create(follower).id
        val targetOwner = member()
        val target = create(targetOwner).id
        makePublic(target)
        val access = bearer(follower)
        requestFollow(access, followerBunch, target)

        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("viewerBunchId", followerBunch.toString())
                .param("page", "0")
                .param("size", "1")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(1))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.items[0].bunch.id").value(followerBunch))
            .andExpect(jsonPath("$.data.items[0].followState").value("NONE"))

        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("viewerBunchId", followerBunch.toString())
                .param("page", "-1")
                .param("size", "1")
        ).expectError(ErrorCode.INVALID_INPUT)

        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("viewerBunchId", followerBunch.toString())
                .param("page", "0")
                .param("size", "0")
        ).expectError(ErrorCode.INVALID_INPUT)

        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("viewerBunchId", followerBunch.toString())
                .param("page", "0")
                .param("size", "51")
        ).expectError(ErrorCode.INVALID_INPUT)

        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("viewerBunchId", followerBunch.toString())
                .param("page", Int.MAX_VALUE.toString())
                .param("size", "50")
        ).expectError(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun `social IDs must be positive and an unavailable target uses the profile-unavailable error`() {
        val viewer = member()
        val viewerBunch = create(viewer).id
        val targetOwner = member()
        val target = create(targetOwner).id
        makePublic(target)
        val access = bearer(viewer)

        mvc.perform(
            get(followersPath(0))
                .header(HttpHeaders.AUTHORIZATION, access)
        ).expectError(ErrorCode.INVALID_INPUT)

        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("viewerBunchId", "0")
        )
            .andExpect(status().isOk)

        mvc.perform(
            put(followingPath(viewerBunch, 0))
                .header(HttpHeaders.AUTHORIZATION, access)
        ).expectError(ErrorCode.INVALID_INPUT)

        mvc.perform(
            delete(followingPath(viewerBunch, target))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("followId", "0")
        ).expectError(ErrorCode.INVALID_INPUT)

        mvc.perform(
            get(followersPath(Long.MAX_VALUE))
                .header(HttpHeaders.AUTHORIZATION, access)
                .param("viewerBunchId", viewerBunch.toString())
        ).expectError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
    }

    @Test
    fun `unauthorized revoked-session and incomplete-signup requests are rejected`() {
        val targetOwner = member()
        val target = create(targetOwner).id
        makePublic(target)

        mvc.perform(get(followersPath(target))).expectError(ErrorCode.UNAUTHORIZED)

        val revokedMember = member()
        val revokedAccess = bearer(revokedMember)
        sessions.delete(tokens.getSessionId(revokedAccess.removePrefix("Bearer ")), revokedMember)
        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, revokedAccess)
        ).expectError(ErrorCode.UNAUTHORIZED)

        val incompleteMember = member(completed = false)
        val incompleteAccess = bearer(incompleteMember)
        mvc.perform(
            get(followersPath(target))
                .header(HttpHeaders.AUTHORIZATION, incompleteAccess)
        ).expectError(ErrorCode.SIGNUP_INCOMPLETE)
    }

    @Test
    fun `block and report HTTP flows enforce role IDs and response shape`() {
        val owner = member()
        val viewer = create(owner).id
        val participant = member()
        join(participant, issue(owner, viewer))
        val target = create(member()).id
        val ownerAccess = bearer(owner)
        val participantAccess = bearer(participant)
        val blockPath = "/api/v1/bunches/$viewer/blocks/$target"
        mvc.perform(put(blockPath).header(HttpHeaders.AUTHORIZATION, participantAccess))
            .expectError(ErrorCode.FORBIDDEN)
        mvc.perform(
            post("/api/v1/bunches/$viewer/reports")
                .header(HttpHeaders.AUTHORIZATION, participantAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"targetBunchId":$target,"reason":"OTHER","detail":" 확인 요청 "}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.reportId").isNumber)
        mvc.perform(
            post("/api/v1/bunches/$viewer/reports")
                .header(HttpHeaders.AUTHORIZATION, participantAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"targetBunchId":$target,"reason":"OTHER","detail":" "}""")
        ).expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(put(blockPath).header(HttpHeaders.AUTHORIZATION, ownerAccess))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isEmpty)
        val list = mvc.perform(
            get("/api/v1/bunches/$viewer/blocks").header(HttpHeaders.AUTHORIZATION, participantAccess)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].bunch.id").value(target))
            .andExpect(jsonPath("$.data.items[0].bunch.introduction").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].blockedAt").exists())
            .andReturn()
        val blockId = list.data().path("items").get(0).path("id").asLong()
        mvc.perform(put(followingPath(viewer, target)).header(HttpHeaders.AUTHORIZATION, ownerAccess))
            .expectError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
        mvc.perform(delete(blockPath).header(HttpHeaders.AUTHORIZATION, ownerAccess))
            .expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(
            delete(blockPath).header(HttpHeaders.AUTHORIZATION, ownerAccess).param("blockId", blockId.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isEmpty)
        mvc.perform(get("/api/v1/bunches/$viewer/blocks").header(HttpHeaders.AUTHORIZATION, ownerAccess))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isEmpty)
    }

    private fun requestFollow(access: String, viewerBunch: Long, target: Long): ResultActions = mvc.perform(
        put(followingPath(viewerBunch, target))
            .header(HttpHeaders.AUTHORIZATION, access)
    )
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.success").value(true))

    private fun decide(
        target: Long,
        requestId: Long,
        access: String,
        decision: String
    ): ResultActions = mvc.perform(
        post(decisionPath(target, requestId))
            .header(HttpHeaders.AUTHORIZATION, access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"decision":"$decision"}""")
    ).andExpect(status().isOk)

    private fun makePublic(bunchId: Long) {
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", bunchId)
    }

    private fun bearer(memberId: Long): String {
        val pair = session(memberId)
        return "Bearer ${pair.accessToken}"
    }

    private fun session(memberId: Long): TokenPair {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return pair
    }

    private fun ResultActions.data(): JsonNode = andReturn().data()

    private fun MvcResult.data(): JsonNode = mapper.readTree(response.contentAsString).path("data")

    private fun ResultActions.expectError(error: ErrorCode): ResultActions = andExpect(status().`is`(error.status))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value(error.code))
        .andExpect(jsonPath("$.error.message").value(error.messageKey))

    private fun followersPath(target: Long) = "/api/v1/bunches/$target/followers"
    private fun followingPath(target: Long) = "/api/v1/bunches/$target/following"
    private fun followingPath(viewer: Long, target: Long) = "/api/v1/bunches/$viewer/following/$target"
    private fun followRequestsPath(viewer: Long) = "/api/v1/bunches/$viewer/follow-requests"
    private fun decisionPath(viewer: Long, requestId: Long) =
        "/api/v1/bunches/$viewer/follow-requests/$requestId/decision"
}
