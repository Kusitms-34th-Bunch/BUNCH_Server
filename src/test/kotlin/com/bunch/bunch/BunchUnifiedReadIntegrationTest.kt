package com.bunch.bunch

import com.bunch.bunch.domain.Visibility
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.time.Duration
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BunchUnifiedReadIntegrationTest : BunchIntegrationSupport() {
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
    fun releaseUnifiedReadFixtures() {
        jdbc.update("DELETE FROM bundles")
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    @Test
    fun `home hides internal data until server current selects target despite viewer query injection`() {
        val actor = member()
        val current = create(actor, "현재 번치").id
        val target = create(actor, "대상 번치").id
        bunches.updateLastUsed(actor, current)
        insertBundle(target, actor, "PUBLISHED", Visibility.PUBLIC)
        insertBundle(target, actor, "PUBLISHED", Visibility.PRIVATE)
        insertBundle(target, actor, "DRAFT", null)

        val auth = session(actor)
        val external = data(
            mvc.perform(
                get("/api/v1/bunches/$target/home")
                    .header(HttpHeaders.AUTHORIZATION, auth.header)
                    .param("viewerBunchId", target.toString())
            )
        )
        val unavailableViewerHome = data(
            mvc.perform(
                get("/api/v1/bunches/$target/home")
                    .header(HttpHeaders.AUTHORIZATION, auth.header)
                    .param("viewerBunchId", Long.MAX_VALUE.toString())
            )
        )

        assertHomeKeys(external)
        assertThat(unavailableViewerHome.path("currentBunchId").asLong()).isEqualTo(current)
        assertThat(unavailableViewerHome.path("accessScope").asText()).isEqualTo("EXTERNAL")
        assertThat(unavailableViewerHome.path("myMembership").isNull).isTrue()
        assertThat(external.path("bunch").path("id").asLong()).isEqualTo(target)
        assertThat(external.path("currentBunchId").asLong()).isEqualTo(current)
        assertThat(external.path("accessScope").asText()).isEqualTo("EXTERNAL")
        assertThat(external.path("isCurrentBunch").asBoolean()).isFalse()
        assertThat(external.path("myMembership").isNull).isTrue()
        assertThat(external.path("profileVersion").isNull).isTrue()
        assertThat(external.path("membershipVersion").isNull).isTrue()
        assertThat(external.path("draftCount").isNull).isTrue()
        assertThat(external.path("publishedCount").isNull).isTrue()
        assertThat(external.path("unreadCount").isNull).isTrue()
        assertThat(external.path("stage").asInt()).isEqualTo(2)
        assertThat(external.path("canViewBundles").asBoolean()).isFalse()
        assertThat(external.path("canViewMembers").asBoolean()).isFalse()
        assertThat(external.path("canViewRelations").asBoolean()).isFalse()
        assertThat(external.path("followState").asText()).isEqualTo("NONE")
        assertThat(external.path("followId").isNull).isTrue()
        assertThat(external.path("permissions").path("canEditProfile").asBoolean()).isFalse()
        assertThat(external.path("permissions").path("canDeleteBunch").asBoolean()).isFalse()
        assertThat(external.path("permissions").path("canManageRoles").asBoolean()).isFalse()
        assertThat(external.path("permissions").path("canTransferOwner").asBoolean()).isFalse()
        assertThat(external.path("permissions").path("canBlock").asBoolean()).isTrue()
        assertThat(external.path("permissions").path("canInvite").asBoolean()).isFalse()
        assertThat(external.path("permissions").path("canLeave").asBoolean()).isFalse()

        bunches.updateLastUsed(actor, target)
        val internal = data(
            mvc.perform(
                get("/api/v1/bunches/$target/home")
                    .header(HttpHeaders.AUTHORIZATION, auth.header)
                    .param("viewerBunchId", current.toString())
            )
        )

        assertHomeKeys(internal)
        assertThat(internal.path("accessScope").asText()).isEqualTo("INTERNAL")
        assertThat(internal.path("currentBunchId").asLong()).isEqualTo(target)
        assertThat(internal.path("isCurrentBunch").asBoolean()).isTrue()
        assertThat(internal.path("myMembership").path("role").asText()).isEqualTo("OWNER")
        assertThat(internal.path("profileVersion").asLong()).isZero()
        assertThat(internal.path("membershipVersion").asLong()).isEqualTo(1)
        assertThat(internal.path("draftCount").asLong()).isEqualTo(1)
        assertThat(internal.path("publishedCount").asLong()).isEqualTo(2)
        assertThat(internal.path("unreadCount").asLong()).isZero()
        assertThat(internal.path("canViewBundles").asBoolean()).isTrue()
        assertThat(internal.path("canViewMembers").asBoolean()).isTrue()
        assertThat(internal.path("canViewRelations").asBoolean()).isTrue()
        assertThat(internal.path("permissions").properties().all { it.value.asBoolean() }).isTrue()
    }

    @Test
    fun `canonical home prioritizes an own notice then resumes public external and internal reads`() {
        val owner = member()
        val removed = member()
        val target = create(owner, "공지 대상").id
        val invitation = issue(owner, target)
        join(removed, invitation)
        val membershipId = jdbc.queryForObject(
            "SELECT id FROM bunch_memberships WHERE member_id = ? AND bunch_id = ? AND status = 'ACTIVE'",
            Long::class.java,
            removed,
            target
        )!!
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", target)
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
            membershipId
        )

        val activeNoticeId = insertNotice(removed, target, membershipId, Instant.now().plus(Duration.ofDays(1)))
        val auth = session(removed).header
        mvc.perform(
            get("/api/v1/bunches/$target/home")
                .header(HttpHeaders.AUTHORIZATION, auth)
        )
            .andExpect(status().`is`(ErrorCode.BUNCH_ACCESS_UNAVAILABLE.status))
            .andExpect(jsonPath("$.error.code").value(ErrorCode.BUNCH_ACCESS_UNAVAILABLE.code))
            .andExpect(jsonPath("$.error.detail.noticeId").value(activeNoticeId))
            .andExpect(jsonPath("$.error.detail.reason").value("REMOVED"))

        jdbc.update("DELETE FROM bunch_access_notices WHERE id = ?", activeNoticeId)
        val afterDelete = data(
            mvc.perform(
                get("/api/v1/bunches/$target/home")
                    .header(HttpHeaders.AUTHORIZATION, auth)
            )
        )
        assertThat(afterDelete.path("accessScope").asText()).isEqualTo("EXTERNAL")
        assertThat(afterDelete.path("currentBunchId").isNull).isTrue()
        assertThat(afterDelete.path("canViewBundles").asBoolean()).isTrue()

        val expiredNoticeId = insertNotice(removed, target, membershipId, Instant.now().minusSeconds(1))
        val afterExpiry = data(
            mvc.perform(
                get("/api/v1/bunches/$target/home")
                    .header(HttpHeaders.AUTHORIZATION, auth)
            )
        )
        assertThat(afterExpiry).isEqualTo(afterDelete)

        join(removed, invitation, UUID.randomUUID())
        val afterRejoin = data(
            mvc.perform(
                get("/api/v1/bunches/$target/home")
                    .header(HttpHeaders.AUTHORIZATION, auth)
            )
        )
        assertThat(afterRejoin.path("accessScope").asText()).isEqualTo("INTERNAL")
        assertThat(afterRejoin.path("currentBunchId").asLong()).isEqualTo(target)
        assertThat(afterRejoin.path("myMembership").path("role").asText()).isEqualTo("MEMBER")
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_access_notices WHERE id = ?",
            Long::class.java,
            expiredNoticeId
        )).isZero()
    }

    @Test
    fun `current bunch is shared across sessions and reads never mutate last used order`() {
        val actor = member()
        val first = create(actor, "첫 번치").id
        val second = create(actor, "둘째 번치").id
        val third = create(actor, "셋째 번치").id
        val tiedAt = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        jdbc.update("UPDATE bunch_memberships SET last_used_at = ? WHERE member_id = ?", tiedAt, actor)

        val firstSession = session(actor)
        val secondSession = session(actor)
        val beforeReads = lastUsedAt(actor)
        val initial = data(
            mvc.perform(
                get("/api/v1/members/me/bunches")
                    .header(HttpHeaders.AUTHORIZATION, firstSession.header)
                    .param("currentBunchId", first.toString())
            )
        )
        assertThat(initial.path("defaultBunchId").asLong()).isEqualTo(third)
        assertThat(bunchIds(initial)).containsExactly(third, second, first)
        assertThat(lastUsedAt(actor)).isEqualTo(beforeReads)

        mvc.perform(
            put("/api/v1/bunches/$first/last-used")
                .header(HttpHeaders.AUTHORIZATION, firstSession.header)
        ).andExpect(status().isOk)

        val afterSwitch = lastUsedAt(actor)
        val secondSessionList = data(
            mvc.perform(
                get("/api/v1/members/me/bunches")
                    .header(HttpHeaders.AUTHORIZATION, secondSession.header)
                    .param("currentBunchId", third.toString())
            )
        )
        val firstSessionList = data(
            mvc.perform(
                get("/api/v1/members/me/bunches")
                    .header(HttpHeaders.AUTHORIZATION, firstSession.header)
                    .param("currentBunchId", third.toString())
            )
        )
        assertThat(secondSessionList.path("defaultBunchId").asLong()).isEqualTo(first)
        assertThat(firstSessionList.path("defaultBunchId").asLong()).isEqualTo(first)
        assertThat(bunchIds(secondSessionList)).containsExactly(first, third, second)
        assertThat(lastUsedAt(actor)).isEqualTo(afterSwitch)

        val homeFromFirstSession = data(
            mvc.perform(
                get("/api/v1/bunches/$first/home")
                    .header(HttpHeaders.AUTHORIZATION, firstSession.header)
                    .param("viewerBunchId", third.toString())
            )
        )
        val homeFromSecondSession = data(
            mvc.perform(
                get("/api/v1/bunches/$first/home")
                    .header(HttpHeaders.AUTHORIZATION, secondSession.header)
                    .param("viewerBunchId", third.toString())
            )
        )
        assertThat(homeFromFirstSession.path("accessScope").asText()).isEqualTo("INTERNAL")
        assertThat(homeFromSecondSession.path("accessScope").asText()).isEqualTo("INTERNAL")
        assertThat(homeFromFirstSession.path("currentBunchId").asLong()).isEqualTo(first)
        assertThat(homeFromSecondSession.path("currentBunchId").asLong()).isEqualTo(first)
        assertThat(lastUsedAt(actor)).isEqualTo(afterSwitch)

        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = CURRENT_TIMESTAMP " +
                "WHERE member_id = ? AND bunch_id = ?",
            actor,
            first
        )
        val fallbackList = data(
            mvc.perform(
                get("/api/v1/members/me/bunches")
                    .header(HttpHeaders.AUTHORIZATION, secondSession.header)
                    .param("currentBunchId", first.toString())
            )
        )
        assertThat(fallbackList.path("defaultBunchId").asLong()).isEqualTo(third)
        assertThat(bunchIds(fallbackList)).containsExactly(third, second)
        val fallbackHome = data(
            mvc.perform(
                get("/api/v1/bunches/$third/home")
                    .header(HttpHeaders.AUTHORIZATION, secondSession.header)
                    .param("viewerBunchId", first.toString())
            )
        )
        assertThat(fallbackHome.path("currentBunchId").asLong()).isEqualTo(third)
        assertThat(fallbackHome.path("accessScope").asText()).isEqualTo("INTERNAL")
        assertThat(lastUsedAt(actor)).isEqualTo(afterSwitch)
    }

    @Test
    fun `canonical members and published bundles keep minimal fields and scope private content internally`() {
        val owner = member()
        val target = create(owner, "공개 대상").id
        val coOwner = member()
        join(coOwner, issue(owner, target))
        jdbc.update(
            "UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE bunch_id = ? AND member_id = ?",
            target,
            coOwner
        )
        val regular = member()
        join(regular, issue(owner, target))
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", target)

        val publicBundle = insertBundle(target, owner, "PUBLISHED", Visibility.PUBLIC)
        val privateBundle = insertBundle(target, owner, "PUBLISHED", Visibility.PRIVATE)
        val draft = insertBundle(target, owner, "DRAFT", null)

        val internalMembers = data(
            mvc.perform(
                get("/api/v1/bunches/$target/members")
                    .header(HttpHeaders.AUTHORIZATION, session(owner).header)
            )
        )
        assertMinimalMembers(internalMembers)
        assertThat(internalMembers.map { it.path("role").asText() })
            .containsExactly("OWNER", "CO_OWNER", "MEMBER")

        val internalPage = data(
            mvc.perform(
                get("/api/v1/bunches/$target/published-bundles")
                    .header(HttpHeaders.AUTHORIZATION, session(owner).header)
            )
        )
        assertPageKeys(internalPage)
        assertThat(internalPage.path("totalElements").asLong()).isEqualTo(2)
        assertThat(internalPage.path("items").map { it.path("id").asLong() })
            .containsExactly(privateBundle, publicBundle)
        assertThat(
            data(
                mvc.perform(
                    get("/api/v1/bunches/$target/published-bundles/$privateBundle")
                        .header(HttpHeaders.AUTHORIZATION, session(owner).header)
                )
            ).path("id").asLong()
        ).isEqualTo(privateBundle)

        val viewer = member()
        val viewerBunch = create(viewer, "현재 viewer").id
        val externalMembers = data(
            mvc.perform(
                get("/api/v1/bunches/$target/members")
                    .header(HttpHeaders.AUTHORIZATION, session(viewer).header)
                    .param("viewerBunchId", target.toString())
            )
        )
        assertMinimalMembers(externalMembers)
        assertThat(externalMembers).isEqualTo(internalMembers)
        assertThat(viewerBunch).isNotEqualTo(target)

        val externalPage = data(
            mvc.perform(
                get("/api/v1/bunches/$target/published-bundles")
                    .header(HttpHeaders.AUTHORIZATION, session(viewer).header)
                    .param("viewerBunchId", target.toString())
            )
        )
        assertPageKeys(externalPage)
        assertThat(externalPage.path("totalElements").asLong()).isEqualTo(1)
        assertThat(externalPage.path("items").single().path("id").asLong()).isEqualTo(publicBundle)
        read(viewer, target, "published-bundles/$privateBundle", target).error(ErrorCode.RESOURCE_NOT_FOUND)
        read(viewer, target, "published-bundles/$draft", target).error(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `removed read paths return 404 for owner and external viewer while canonical reads work`() {
        val owner = member()
        val target = create(owner, "공개 조회 대상").id
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", target)
        val bundleId = insertBundle(target, owner, "PUBLISHED", Visibility.PUBLIC)
        val visitor = member()
        val removedPaths = listOf(
            "public-profile", "public-members", "public-bundles", "public-bundles/$bundleId"
        )

        listOf(owner to "INTERNAL", visitor to "EXTERNAL").forEach { (memberId, expectedScope) ->
            val auth = session(memberId).header
            val home = data(mvc.perform(
                get("/api/v1/bunches/$target/home").header(HttpHeaders.AUTHORIZATION, auth)
            ))
            assertHomeKeys(home)
            assertThat(home.path("bunch").path("id").asLong()).isEqualTo(target)
            assertThat(home.path("accessScope").asText()).isEqualTo(expectedScope)
            if (memberId == owner) {
                assertThat(home.path("currentBunchId").asLong()).isEqualTo(target)
            } else {
                assertThat(home.path("currentBunchId").isNull).isTrue()
            }

            val people = data(mvc.perform(
                get("/api/v1/bunches/$target/members").header(HttpHeaders.AUTHORIZATION, auth)
            ))
            assertMinimalMembers(people)
            assertThat(people.single().path("memberId").asLong()).isEqualTo(owner)
            val page = data(mvc.perform(
                get("/api/v1/bunches/$target/published-bundles").header(HttpHeaders.AUTHORIZATION, auth)
            ))
            assertPageKeys(page)
            assertThat(page.path("totalElements").asLong()).isEqualTo(1)
            assertThat(page.path("items").single().path("id").asLong()).isEqualTo(bundleId)
            val detail = data(mvc.perform(
                get("/api/v1/bunches/$target/published-bundles/$bundleId")
                    .header(HttpHeaders.AUTHORIZATION, auth)
            ))
            assertThat(detail.path("id").asLong()).isEqualTo(bundleId)

            removedPaths.forEach { path ->
                mvc.perform(
                    get("/api/v1/bunches/$target/$path").header(HttpHeaders.AUTHORIZATION, auth)
                ).andExpect(status().isNotFound)
            }
        }
    }

    private fun assertHomeKeys(node: JsonNode) {
        assertThat(node.fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrderElementsOf(HOME_KEYS)
        assertThat(node.path("permissions").fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrderElementsOf(PERMISSION_KEYS)
    }

    private fun assertMinimalMembers(node: JsonNode) {
        assertThat(node.isArray).isTrue()
        assertThat(node.map { it.fieldNames().asSequence().toSet() }.toSet())
            .containsOnly(MEMBER_KEYS)
    }

    private fun assertPageKeys(node: JsonNode) {
        assertThat(node.fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("items", "page", "size", "totalElements", "hasNext")
    }

    private fun bunchIds(node: JsonNode): List<Long> =
        node.path("activeBunches").map { it.path("bunch").path("id").asLong() }

    private fun lastUsedAt(memberId: Long): Map<Long, Instant> = jdbc.query(
        "SELECT bunch_id, last_used_at FROM bunch_memberships WHERE member_id = ? ORDER BY bunch_id",
        { rs, _ -> rs.getLong("bunch_id") to rs.getTimestamp("last_used_at").toInstant() },
        memberId
    ).toMap()

    private fun insertBundle(
        bunchId: Long,
        createdBy: Long,
        status: String,
        visibility: Visibility?
    ): Long = jdbc.queryForObject(
        """INSERT INTO bundles
            (bunch_id, created_by, status, title, visibility, created_at, updated_at, published_at)
            VALUES (?, ?, ?, 'fixture', ?, '2026-01-01', '2026-01-01',
                CASE WHEN ? = 'PUBLISHED' THEN '2026-01-01'::timestamptz ELSE NULL END)
            RETURNING id""",
        Long::class.java,
        bunchId,
        createdBy,
        status,
        visibility?.name,
        status
    )!!

    private fun insertNotice(
        recipientMemberId: Long,
        bunchId: Long,
        membershipId: Long,
        expiresAt: Instant
    ): Long = jdbc.queryForObject(
        """INSERT INTO bunch_access_notices
            (recipient_member_id, bunch_id, membership_id, bunch_name, character_type, reason, occurred_at, expires_at)
            VALUES (?, ?, ?, '공지 대상', 'TYPE_2', 'REMOVED', ?, ?)
            RETURNING id""",
        Long::class.java,
        recipientMemberId,
        bunchId,
        membershipId,
        Timestamp.from(expiresAt.minus(Duration.ofDays(30))),
        Timestamp.from(expiresAt)
    )!!

    private fun read(userId: Long, targetId: Long, path: String, ignoredViewerId: Long? = null): ResultActions {
        val request = get("/api/v1/bunches/$targetId/$path")
            .header(HttpHeaders.AUTHORIZATION, session(userId).header)
        if (ignoredViewerId != null) request.param("viewerBunchId", ignoredViewerId.toString())
        return mvc.perform(request)
    }

    private fun data(result: ResultActions): JsonNode =
        mapper.readTree(result.andExpect(status().isOk).andReturn().response.contentAsString).path("data")

    private fun ResultActions.error(code: ErrorCode) = andExpect(status().`is`(code.status))

    private fun session(memberId: Long): AuthSession {
        val sessionId = UUID.randomUUID().toString()
        val pair = tokens.generateToken(memberId, "ROLE_USER", sessionId)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return AuthSession("Bearer ${pair.accessToken}")
    }

    private data class AuthSession(val header: String)

    companion object {
        private val HOME_KEYS = setOf(
            "bunch", "myMembership", "permissions", "profileVersion", "membershipVersion",
            "memberCount", "followerCount", "followingCount", "draftCount", "publishedCount",
            "stage", "unreadCount", "currentBunchId", "accessScope", "followState", "followId",
            "canViewBundles", "canViewMembers", "canViewRelations", "isCurrentBunch"
        )
        private val PERMISSION_KEYS = setOf(
            "canEditProfile", "canDeleteBunch", "canManageRoles", "canTransferOwner", "canBlock",
            "canInvite", "canLeave"
        )
        private val MEMBER_KEYS = setOf("memberId", "nickname", "imageUrl", "role")
    }
}
