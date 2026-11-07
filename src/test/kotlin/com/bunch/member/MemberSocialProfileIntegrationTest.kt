package com.bunch.member

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.dto.FollowDecision
import com.bunch.bunch.dto.FollowState
import com.bunch.bunch.service.BunchSocialService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.member.dto.MemberSocialBunchItem
import com.bunch.member.service.MemberSocialProfileService
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class MemberSocialProfileIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var profiles: MemberSocialProfileService

    @Autowired
    private lateinit var social: BunchSocialService

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    private val sessionIds = mutableListOf<Pair<String, Long>>()
    private val imageIds = mutableListOf<Long>()

    @AfterEach
    fun cleanupProfileFixtures() {
        sessionIds.forEach { (sid, id) -> sessions.delete(sid, id) }
        imageIds.forEach {
            jdbc.update("UPDATE members SET profile_image_asset_id = NULL WHERE profile_image_asset_id = ?", it)
            jdbc.update("UPDATE bunches SET profile_image_asset_id = NULL WHERE profile_image_asset_id = ?", it)
            jdbc.update("DELETE FROM image_assets WHERE id = ?", it)
        }
        jdbc.update("DELETE FROM feed_content_hides")
    }

    @Test
    fun `HTTP returns exact public fields and private bunch basics without leaking account or content data`() {
        val viewer = member()
        val target = member()
        val bunch = addBunch(target, "비공개번치")
        val image = image()
        jdbc.update("UPDATE members SET birth_year = 2000, gender = 'FEMALE', profile_image_asset_id = ? WHERE id = ?",
            image, target)
        jdbc.update("UPDATE bunches SET profile_image_asset_id = ? WHERE id = ?", image, bunch)

        val response = mvc.perform(get(path(target)).header("Authorization", bearer(viewer)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.memberId").value(target))
            .andExpect(jsonPath("$.data.imageUrl").value("https://example.com/public.jpg"))
            .andExpect(jsonPath("$.data.bunches[0].bunch.imageUrl").value("https://example.com/public.jpg"))
            .andExpect(jsonPath("$.data.bunches[0].bunch.visibility").value("PRIVATE"))
            .andExpect(jsonPath("$.data.bunches[0].showFollowButton").value(false))
            .andReturn().response.contentAsString
        val data = mapper.readTree(response).path("data")
        assertThat(data.fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("memberId", "nickname", "imageUrl", "currentBunchId", "bunches")
        assertThat(data.path("nickname").asText()).isNotBlank()
        val row = data.path("bunches").get(0)
        assertThat(row.fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("bunch", "followState", "followId", "showFollowButton")
        assertThat(row.path("bunch").fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("id", "name", "imageUrl", "characterType", "visibility")
    }

    @Test
    fun `active memberships are naturally sorted with stable ID ties and no row limit`() {
        val target = member()
        val names = listOf("10반", "3반", "bookClub", "BookClub", "퇴근런클럽", "여행가조", "반10", "반2")
        val ids = names.map { addBunch(target, it) }
        val result = profiles.get(member(), target)
        assertThat(result.bunches.map { it.bunch.name })
            .containsExactly("반2", "반10", "여행가조", "퇴근런클럽", "bookClub", "BookClub", "3반", "10반")
        assertThat(result.bunches.filter { it.bunch.name.equals("bookclub", true) }.map { it.bunch.id })
            .containsExactly(ids[2], ids[3])
        assertThat(result.bunches.map { it.bunch.id }.distinct()).hasSize(names.size)
    }

    @Test
    fun `follow buttons reuse existing public private approval and cancellation states`() {
        val viewer = member()
        val current = create(viewer).id
        val target = member()
        join(target, issue(viewer, current))
        val public = addBunch(target, "공개번치", true)
        val private = addBunch(target, "비공개번치")
        val none = addBunch(target, "미팔로우")
        val approved = social.follow(viewer, current, public)
        val pending = social.follow(viewer, current, private)
        val rows = profiles.get(viewer, target).bunches.associateBy { it.bunch.id }
        assertThat(rows.getValue(current).showFollowButton).isFalse()
        assertThat(rows.getValue(current).followState).isEqualTo(FollowState.NONE)
        assertThat(rows.getValue(public).followState).isEqualTo(FollowState.FOLLOWING)
        assertThat(rows.getValue(public).followId).isEqualTo(approved.followId)
        assertThat(rows.getValue(private).followState).isEqualTo(FollowState.REQUESTED)
        assertThat(rows.getValue(private).followId).isEqualTo(pending.followId)
        assertThat(rows.getValue(none).followState).isEqualTo(FollowState.NONE)
        assertThat(rows.getValue(none).followId).isNull()
        assertThat(rows.filterKeys { it != current }.values).allMatch { it.showFollowButton }
        val owner = jdbc.queryForObject("SELECT created_by FROM bunches WHERE id = ?", Long::class.java, private)!!
        social.decide(owner, private, pending.followId!!, FollowDecision.APPROVE)
        assertThat(row(viewer, target, private).followState).isEqualTo(FollowState.FOLLOWING)
        social.cancel(viewer, current, public, approved.followId!!)
        assertThat(row(viewer, target, public).followState).isEqualTo(FollowState.NONE)
        assertThat(row(viewer, target, public).followId).isNull()
    }

    @Test
    fun `left and removed memberships disappear and same account rejoin restores the row`() {
        val target = member()
        val left = addBunch(target, "나간번치")
        val removed = addBunch(target, "내보낸번치")
        val active = addBunch(target, "활성번치")
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT', ended_at = now() WHERE bunch_id = ? AND member_id = ?",
            left, target)
        jdbc.update("UPDATE bunch_memberships SET status = 'REMOVED', ended_at = now() WHERE bunch_id = ? AND member_id = ?",
            removed, target)
        val visitor = member()
        assertThat(profiles.get(visitor, target).bunches.map { it.bunch.id }).containsExactly(active)
        jdbc.update("UPDATE bunch_memberships SET status = 'ACTIVE', ended_at = NULL WHERE bunch_id = ? AND member_id = ?",
            left, target)
        assertThat(profiles.get(visitor, target).bunches.map { it.bunch.id }).containsExactly(left, active)
        jdbc.update("DELETE FROM bunches WHERE id = ?", active)
        assertThat(profiles.get(visitor, target).bunches.map { it.bunch.id }).containsExactly(left)
    }

    @Test
    fun `both block directions and shared report operator hides filter before returning rows`() {
        val viewer = member()
        val current = create(viewer).id
        val target = member()
        val outgoing = addBunch(target, "차단보냄")
        val incoming = addBunch(target, "차단받음")
        val reported = addBunch(target, "신고")
        val hidden = addBunch(target, "운영숨김")
        val scoped = addBunch(target, "신고숨김")
        val visible = addBunch(target, "보임")
        social.block(viewer, current, outgoing)
        val owner = jdbc.queryForObject("SELECT created_by FROM bunches WHERE id = ?", Long::class.java, incoming)!!
        social.block(owner, incoming, current)
        social.report(viewer, current, BunchReportRequest(reported, BunchReportReason.SPAM))
        jdbc.update("INSERT INTO feed_content_hides (target_kind, target_id, reason) VALUES ('BUNCH', ?, 'review')", hidden)
        jdbc.update("""INSERT INTO feed_content_hides (target_kind, target_id, reporter_bunch_id, reason)
            VALUES ('BUNCH', ?, ?, 'REPORTED')""", scoped, current)
        assertThat(profiles.get(viewer, target).bunches.map { it.bunch.id }).containsExactly(visible)
        assertThat(profiles.get(member(), target).bunches.map { it.bunch.id })
            .containsExactlyInAnyOrder(outgoing, incoming, reported, scoped, visible)
        jdbc.update("DELETE FROM feed_content_hides WHERE reporter_bunch_id IS NULL")
        assertThat(profiles.get(viewer, target).bunches.map { it.bunch.id }).containsExactly(visible, hidden)
    }

    @Test
    fun `server current switch rechecks relationships and blocks and ignores spoofed request selection`() {
        val viewer = member()
        val first = create(viewer).id
        val second = create(viewer).id
        val target = member()
        val bunch = addBunch(target, "대상번치", true)
        social.follow(viewer, first, bunch)
        social.block(viewer, second, bunch)
        selectCurrent(viewer, first)
        mvc.perform(get(path(target)).header("Authorization", bearer(viewer)).param("currentBunchId", second.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.currentBunchId").value(first))
            .andExpect(jsonPath("$.data.bunches[0].followState").value("FOLLOWING"))
        selectCurrent(viewer, second)
        assertThat(profiles.get(viewer, target).currentBunchId).isEqualTo(second)
        assertThat(profiles.get(viewer, target).bunches).isEmpty()
    }

    @Test
    fun `zero memberships are allowed for viewer and target while follow still requires an actor`() {
        val viewer = member()
        val target = member()
        assertThat(profiles.get(viewer, target).bunches).isEmpty()
        assertThat(profiles.get(viewer, target).currentBunchId).isNull()
        assertThat(profiles.get(viewer, target).imageUrl).isNull()
        val bunch = addBunch(target, "비공개번치")
        assertThat(row(viewer, target, bunch).showFollowButton).isFalse()
        mvc.perform(put("/api/v1/bunches/$bunch/following/${create(member()).id}")
            .header("Authorization", bearer(viewer)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("BUNCH_010"))
    }

    @Test
    fun `inactive incomplete and missing targets all return the same not found error`() {
        val viewer = member()
        val target = member()
        listOf("SUSPENDED", "WITHDRAWN").forEach { state ->
            jdbc.update("UPDATE members SET account_status = ? WHERE id = ?", state, target)
            mvc.perform(get(path(target)).header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.error.code").value("USER_001"))
        }
        assertError(ErrorCode.USER_NOT_FOUND) { profiles.get(viewer, member(false)) }
        assertError(ErrorCode.USER_NOT_FOUND) { profiles.get(viewer, Long.MAX_VALUE) }
        assertError(ErrorCode.INVALID_INPUT) { profiles.get(viewer, 0) }
        assertError(ErrorCode.INVALID_INPUT) { profiles.get(viewer, -1) }
    }

    @Test
    fun `HTTP requires a live Redis session and completed active viewer`() {
        val target = member()
        mvc.perform(get(path(target))).andExpect(status().isUnauthorized)
        mvc.perform(get(path(target)).header("Authorization", bearer(member(false))))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("AUTH_015"))
        val viewer = member()
        val access = bearer(viewer)
        jdbc.update("UPDATE members SET account_status = 'SUSPENDED' WHERE id = ?", viewer)
        mvc.perform(get(path(target)).header("Authorization", access)).andExpect(status().isUnauthorized)
        jdbc.update("UPDATE members SET account_status = 'ACTIVE' WHERE id = ?", viewer)
        val sid = sessionIds.last().first
        sessions.delete(sid, viewer)
        mvc.perform(get(path(target)).header("Authorization", access)).andExpect(status().isUnauthorized)
        mvc.perform(get(path(0)).header("Authorization", bearer(viewer)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_001"))
    }

    @Test
    fun `duplicate concurrent profile reads preserve memberships follows and notifications`() {
        val viewer = member()
        val current = create(viewer).id
        val target = member()
        val bunch = addBunch(target, "읽기번치", true)
        social.follow(viewer, current, bunch)
        val before = listOf(count("bunch_memberships"), count("bunch_follows"), count("notifications"))
        val expected = profiles.get(viewer, target)
        val results = concurrently(List(4) { { profiles.get(viewer, target) } })
        assertThat(results.map { it.getOrThrow() }).containsOnly(expected)
        assertThat(listOf(count("bunch_memberships"), count("bunch_follows"), count("notifications")))
            .isEqualTo(before)
    }

    private fun addBunch(target: Long, name: String, public: Boolean = false): Long {
        val bunch = create(member(), name).id
        jdbc.update("""INSERT INTO bunch_memberships (bunch_id, member_id, role, status, joined_at, last_used_at,
            role_granted_at) VALUES (?, ?, 'MEMBER', 'ACTIVE', now(), now(), now())""", bunch, target)
        if (public) jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", bunch)
        return bunch
    }

    private fun row(viewer: Long, target: Long, bunch: Long): MemberSocialBunchItem =
        profiles.get(viewer, target).bunches.single { it.bunch.id == bunch }

    private fun selectCurrent(viewer: Long, bunch: Long) {
        jdbc.update("UPDATE bunch_memberships SET last_used_at = '2026-01-01' WHERE member_id = ?", viewer)
        jdbc.update("UPDATE bunch_memberships SET last_used_at = '2026-01-02' WHERE member_id = ? AND bunch_id = ?",
            viewer, bunch)
    }

    private fun image(): Long = jdbc.queryForObject(
        "INSERT INTO image_assets (public_id, url, created_at) VALUES (?, ?, now()) RETURNING id",
        Long::class.java,
        "profile-test-${UUID.randomUUID()}",
        "https://example.com/public.jpg"
    )!!.also { imageIds.add(it) }

    private fun bearer(userId: Long): String {
        val sid = UUID.randomUUID().toString()
        val pair = tokens.generateToken(userId, "ROLE_USER", sid)
        sessions.save(sid, userId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds.add(sid to userId)
        return "Bearer ${pair.accessToken}"
    }

    private fun path(memberId: Long) = "/api/v1/members/$memberId/social-profile"
}
