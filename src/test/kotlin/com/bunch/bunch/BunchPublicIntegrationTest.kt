package com.bunch.bunch

import com.bunch.bunch.dto.FollowDecision
import com.bunch.bunch.service.BunchSocialService
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BunchPublicIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var social: BunchSocialService

    private val sessionIds = mutableListOf<Pair<String, Long>>()
    private val assetIds = mutableListOf<Long>()

    @AfterEach
    fun releasePublicFixtures() {
        jdbc.update("DELETE FROM bundles")
        assetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        sessionIds.forEach { (sid, memberId) -> sessions.delete(sid, memberId) }
    }

    @Test
    fun `private home exposes actual stage but viewer query cannot borrow target membership`() {
        val actor = member()
        val viewer = create(actor).id
        val target = create(actor).id
        bunches.updateLastUsed(actor, viewer)
        published(target, actor, "PUBLIC")
        published(target, actor, "PRIVATE")
        val profile = data(read(actor, target, "home", target))
        assertThat(profile.path("stage").asInt()).isEqualTo(2)
        assertThat(profile.path("memberCount").asLong()).isEqualTo(1)
        assertThat(profile.path("currentBunchId").asLong()).isEqualTo(viewer)
        assertThat(profile.path("accessScope").asText()).isEqualTo("EXTERNAL")
        assertThat(profile.path("myMembership").isNull).isTrue()
        assertThat(profile.path("profileVersion").isNull).isTrue()
        assertThat(profile.path("membershipVersion").isNull).isTrue()
        assertThat(profile.path("draftCount").isNull).isTrue()
        assertThat(profile.path("publishedCount").isNull).isTrue()
        assertThat(profile.path("unreadCount").isNull).isTrue()
        assertThat(profile.path("canViewBundles").asBoolean()).isFalse()
        assertThat(profile.path("canViewMembers").asBoolean()).isFalse()
        assertThat(profile.path("canViewRelations").asBoolean()).isFalse()
        assertThat(profile.path("isCurrentBunch").asBoolean()).isFalse()
        assertThat(profile.path("followState").asText()).isEqualTo("NONE")
        assertThat(profile.path("followId").isNull).isTrue()
        assertThat(profile.path("permissions").fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "canEditProfile", "canDeleteBunch", "canManageRoles", "canTransferOwner",
            "canBlock", "canInvite", "canLeave"
        )
        assertThat(profile.path("permissions").path("canEditProfile").asBoolean()).isFalse()
        assertThat(profile.path("permissions").path("canDeleteBunch").asBoolean()).isFalse()
        assertThat(profile.path("permissions").path("canManageRoles").asBoolean()).isFalse()
        assertThat(profile.path("permissions").path("canTransferOwner").asBoolean()).isFalse()
        assertThat(profile.path("permissions").path("canBlock").asBoolean()).isTrue()
        assertThat(profile.path("permissions").path("canInvite").asBoolean()).isFalse()
        assertThat(profile.path("permissions").path("canLeave").asBoolean()).isFalse()
        assertThat(profile.path("bunch").fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "id", "name", "introduction", "imageUrl", "characterType", "visibility"
        )
        listOf("members", "published-bundles", "followers", "following").forEach {
            read(actor, target, it, viewer).error(ErrorCode.FORBIDDEN)
        }
        val pendingId = social.follow(actor, viewer, target).followId!!
        read(actor, target, "published-bundles", target).error(ErrorCode.FORBIDDEN)
        social.decide(actor, target, pendingId, FollowDecision.APPROVE)
        read(actor, target, "members", target).andExpect(status().isOk)
        val visible = data(read(actor, target, "published-bundles", target))
        assertThat(visible.path("totalElements").asLong()).isEqualTo(1)
        val approved = data(read(actor, target, "home", target))
        assertThat(approved.path("canViewBundles").asBoolean()).isTrue()
        assertThat(approved.path("canViewMembers").asBoolean()).isTrue()
        assertThat(approved.path("canViewRelations").asBoolean()).isTrue()
        assertThat(approved.path("followState").asText()).isEqualTo("FOLLOWING")
        assertThat(approved.path("followId").asLong()).isEqualTo(pendingId)
        social.cancel(actor, viewer, target, pendingId)
        read(actor, target, "published-bundles", target).error(ErrorCode.FORBIDDEN)
    }

    @Test
    fun `canonical reads permit zero memberships and apply bilateral blocks to every external route`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundleId = published(target, owner, "PUBLIC")
        val visitor = member()
        val visitorProfile = data(read(visitor, target, "home"))
        assertThat(visitorProfile.path("currentBunchId").isNull).isTrue()
        assertThat(visitorProfile.path("accessScope").asText()).isEqualTo("EXTERNAL")
        assertThat(visitorProfile.path("myMembership").isNull).isTrue()
        assertThat(visitorProfile.path("permissions").path("canBlock").asBoolean()).isFalse()
        assertThat(visitorProfile.path("permissions").path("canEditProfile").asBoolean()).isFalse()
        assertThat(visitorProfile.path("permissions").path("canInvite").asBoolean()).isFalse()
        assertThat(visitorProfile.path("followState").asText()).isEqualTo("NONE")
        assertThat(visitorProfile.path("followId").isNull).isTrue()
        read(visitor, target, "members").andExpect(status().isOk)
        read(visitor, target, "published-bundles/$bundleId").andExpect(status().isOk)
        val viewerOwner = member()
        val viewer = create(viewerOwner).id
        val viewerProfile = data(read(viewerOwner, target, "home", target))
        assertThat(viewerProfile.path("currentBunchId").asLong()).isEqualTo(viewer)
        assertThat(viewerProfile.path("accessScope").asText()).isEqualTo("EXTERNAL")
        read(member(false), target, "home").error(ErrorCode.SIGNUP_INCOMPLETE)
        mvc.perform(get("/api/v1/bunches/$target/home")).andExpect(status().isUnauthorized)
        social.block(owner, target, viewer)
        listOf(
            "home", "members", "published-bundles", "published-bundles/$bundleId", "followers", "following"
        ).forEach {
            read(viewerOwner, target, it, viewer).error(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
        }
        read(owner, viewer, "home", target).error(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
        read(visitor, Long.MAX_VALUE, "home").error(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)

        val privateTarget = create(owner).id
        val privateBundle = published(privateTarget, owner, "PUBLIC")
        val zeroMembership = member()
        val privateProfile = data(read(zeroMembership, privateTarget, "home"))
        assertThat(privateProfile.path("currentBunchId").isNull).isTrue()
        assertThat(privateProfile.path("accessScope").asText()).isEqualTo("EXTERNAL")
        assertThat(privateProfile.path("canViewBundles").asBoolean()).isFalse()
        assertThat(privateProfile.path("canViewMembers").asBoolean()).isFalse()
        assertThat(privateProfile.path("canViewRelations").asBoolean()).isFalse()
        read(zeroMembership, privateTarget, "members").error(ErrorCode.FORBIDDEN)
        read(zeroMembership, privateTarget, "published-bundles/$privateBundle").error(ErrorCode.FORBIDDEN)
    }

    @Test
    fun `profile counts and paged relation rows share the viewer block filter`() {
        val targetOwner = member()
        val target = publicBunch(targetOwner)
        val viewerOwner = member()
        val viewer = publicBunch(viewerOwner)
        val hiddenOwner = member()
        val hidden = publicBunch(hiddenOwner)
        val visibleOwner = member()
        val visible = publicBunch(visibleOwner)
        social.follow(viewerOwner, viewer, target)
        social.follow(hiddenOwner, hidden, target)
        social.follow(visibleOwner, visible, target)
        social.follow(targetOwner, target, hidden)
        social.follow(targetOwner, target, visible)
        social.block(hiddenOwner, hidden, viewer)
        val profile = data(read(viewerOwner, target, "home", viewer))
        assertThat(profile.path("accessScope").asText()).isEqualTo("EXTERNAL")
        assertThat(profile.path("publishedCount").isNull).isTrue()
        val followers = data(read(viewerOwner, target, "followers", viewer))
        val following = data(read(viewerOwner, target, "following", viewer))
        assertThat(profile.path("followerCount").asLong()).isEqualTo(2)
        assertThat(profile.path("followingCount").asLong()).isEqualTo(1)
        assertThat(followers.path("totalElements").asLong()).isEqualTo(2)
        assertThat(following.path("totalElements").asLong()).isEqualTo(1)
        assertThat(followers.path("items").map { it.path("bunch").path("id").asLong() })
            .containsExactly(visible, viewer)
        assertThat(following.path("items").single().path("bunch").path("id").asLong()).isEqualTo(visible)
    }

    @Test
    fun `published bundle list detail and pagination omit private external content and recheck changes`() {
        val owner = member()
        val target = publicBunch(owner)
        val viewerOwner = member()
        val viewer = create(viewerOwner).id
        val first = published(target, owner, "PUBLIC")
        val last = published(target, owner, "PUBLIC")
        val privateId = published(target, owner, "PRIVATE")
        val draft = published(target, owner, null)
        val photoId = photo(last, owner)
        val auth = bearer(viewerOwner)
        val result = data(mvc.perform(
            get("/api/v1/bunches/$target/published-bundles")
                .header("Authorization", auth)
                .param("viewerBunchId", target.toString())
                .param("size", "1")
        ))
        assertThat(result.path("totalElements").asLong()).isEqualTo(2)
        assertThat(result.path("hasNext").asBoolean()).isTrue()
        assertThat(result.path("items").single().path("id").asLong()).isEqualTo(last)
        assertThat(result.path("items").single().fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "id", "bunchId", "title", "coverImageUrl", "date", "location", "publishedAt"
        )
        val detail = data(read(viewerOwner, target, "published-bundles/$last", target))
        assertThat(detail.path("photos").single().path("id").asLong()).isEqualTo(photoId)
        assertThat(detail.path("photos").single().fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "id", "imageUrl", "position", "caption"
        )
        assertThat(detail.path("location").isNull).isTrue()
        assertThat(detail.has("permissions")).isFalse()
        assertThat(detail.has("leases")).isFalse()
        val internalPage = data(mvc.perform(
            get("/api/v1/bunches/$target/published-bundles")
                .header("Authorization", bearer(owner))
        ))
        assertThat(internalPage.path("totalElements").asLong()).isEqualTo(3)
        assertThat(internalPage.path("items").map { it.path("id").asLong() })
            .containsExactly(privateId, last, first)
        val internalDetail = data(read(owner, target, "published-bundles/$privateId"))
        assertThat(internalDetail.path("id").asLong()).isEqualTo(privateId)
        read(viewerOwner, target, "published-bundles/$privateId", viewer).error(ErrorCode.RESOURCE_NOT_FOUND)
        read(viewerOwner, target, "published-bundles/$draft", viewer).error(ErrorCode.RESOURCE_NOT_FOUND)
        val otherTarget = publicBunch(member())
        read(viewerOwner, otherTarget, "published-bundles/$first", viewer).error(ErrorCode.RESOURCE_NOT_FOUND)
        mvc.perform(
            get("/api/v1/bunches/$target/published-bundles")
                .header("Authorization", auth).param("viewerBunchId", viewer.toString()).param("size", "51")
        ).error(ErrorCode.INVALID_INPUT)
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", last)
        read(viewerOwner, target, "published-bundles/$last", viewer).error(ErrorCode.RESOURCE_NOT_FOUND)
        jdbc.update("UPDATE bunches SET visibility = 'PRIVATE' WHERE id = ?", target)
        read(viewerOwner, target, "published-bundles/$first", viewer).error(ErrorCode.FORBIDDEN)
    }

    @Test
    fun `members array and published attribution minimize personal data and restore rejoined profiles`() {
        val owner = member()
        val target = publicBunch(owner)
        val coOwner = member()
        join(coOwner, issue(owner, target))
        jdbc.update("UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE bunch_id = ? AND member_id = ?", target, coOwner)
        val returning = member()
        join(returning, issue(owner, target))
        val withdrawn = member()
        join(withdrawn, issue(owner, target))
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", withdrawn)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ? AND member_id = ?", target, returning)
        val bundleId = published(target, owner, "PUBLIC")
        listOf(returning, withdrawn).forEach { id ->
            jdbc.update("INSERT INTO bundle_participants (bundle_id,member_id,joined_at) VALUES (?,?,now())", bundleId, id)
            jdbc.update(
                """INSERT INTO bundle_comments (bundle_id,member_id,content,created_at,updated_at)
                    VALUES (?,?,'kept comment',now(),now())""", bundleId, id
            )
        }
        val visitor = member()
        val people = data(read(visitor, target, "members"))
        assertThat(people.isArray).isTrue()
        assertThat(people.map { it.fieldNames().asSequence().toSet() }.toSet()).containsOnly(
            setOf("memberId", "nickname", "imageUrl", "role")
        )
        assertThat(people.map { it.path("role").asText() }).containsExactly("OWNER", "CO_OWNER")
        assertThat(people.first().fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "memberId", "nickname", "imageUrl", "role"
        )
        assertThat(data(read(visitor, target, "home")).path("memberCount").asLong()).isEqualTo(2)
        val old = data(read(visitor, target, "published-bundles/$bundleId"))
        assertThat(old.path("comments").first().path("nickname").asText()).isEqualTo("나간 멤버")
        assertThat(old.path("comments").first().path("content").asText()).isEqualTo("kept comment")
        assertThat(old.path("comments")[1].path("content").isNull).isTrue()
        jdbc.update("UPDATE members SET nickname = ? WHERE id = ?", "복귀${returning}", returning)
        join(returning, issue(owner, target))
        val restored = data(read(visitor, target, "published-bundles/$bundleId"))
        assertThat(restored.path("contributors").first().path("nickname").asText()).isEqualTo("복귀${returning}")
        assertThat(restored.path("comments").first().path("nickname").asText()).isEqualTo("복귀${returning}")
        assertThat(restored.path("comments")).hasSize(2)
    }

    private fun publicBunch(owner: Long): Long = create(owner).id.also {
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", it)
    }

    private fun published(bunchId: Long, owner: Long, visibility: String?): Long = jdbc.queryForObject(
        """INSERT INTO bundles (bunch_id,created_by,status,title,visibility,created_at,updated_at,published_at)
            VALUES (?,?,?,'public title',?,'2026-01-01','2026-01-01',
                CASE WHEN ? = 'PUBLISHED' THEN '2026-01-01'::timestamptz ELSE NULL END) RETURNING id""",
        Long::class.java, bunchId, owner, if (visibility == null) "DRAFT" else "PUBLISHED", visibility,
        if (visibility == null) "DRAFT" else "PUBLISHED"
    )!!

    private fun photo(bundleId: Long, owner: Long): Long {
        val asset = jdbc.queryForObject(
            "INSERT INTO image_assets (public_id,url,created_at) VALUES (?,'https://example.com/public.jpg',now()) RETURNING id",
            Long::class.java, "public-test/${UUID.randomUUID()}"
        )!!
        assetIds += asset
        return jdbc.queryForObject(
            "INSERT INTO bundle_photos (bundle_id,image_asset_id,position,added_by,added_at) VALUES (?,?,0,?,now()) RETURNING id",
            Long::class.java, bundleId, asset, owner
        )!!
    }

    private fun read(userId: Long, targetId: Long, path: String, viewerId: Long? = null): ResultActions {
        val request = get("/api/v1/bunches/$targetId/$path").header("Authorization", bearer(userId))
        if (viewerId != null) request.param("viewerBunchId", viewerId.toString())
        return mvc.perform(request)
    }

    private fun bearer(userId: Long): String {
        val pair = tokens.generateToken(userId, "ROLE_USER")
        val sid = tokens.getSessionId(pair.accessToken)
        sessions.save(sid, userId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sid to userId
        return "Bearer ${pair.accessToken}"
    }

    private fun data(result: ResultActions): JsonNode =
        mapper.readTree(result.andExpect(status().isOk).andReturn().response.contentAsString).path("data")

    private fun ResultActions.error(code: ErrorCode) = andExpect(status().`is`(code.status))
        .andExpect(jsonPath("$.error.code").value(code.code))
}
