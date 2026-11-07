package com.bunch.feed

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.feed.domain.FeedQueryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
class FeedQueryIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var query: FeedQueryRepository

    private val anchor = Instant.parse("2026-11-07T12:34:56.123456Z")
    private val since = anchor.minusSeconds(14 * 86_400L)

    @AfterEach
    fun cleanFeed() {
        jdbc.update("DELETE FROM feed_content_hides")
        jdbc.update("DELETE FROM bundles")
    }

    @Test
    fun `two segments exclude all active memberships and split approved recent public bundles exactly`() {
        val user = member()
        val current = publicBunch(user)
        val otherMembership = publicBunch(user)
        val owner = member()
        val followed = publicBunch(owner)
        val privateFollowed = create(owner).id
        val pending = create(owner).id
        val stranger = publicBunch(owner)
        follow(current, followed)
        follow(current, privateFollowed)
        follow(current, pending, "PENDING")
        val atAnchor = published(followed, owner, anchor)
        val atLowerBoundary = published(privateFollowed, owner, since)
        val oldFollowed = published(followed, owner, since.minusNanos(1_000))
        val publicOther = published(stranger, owner, anchor.minusSeconds(1))
        published(current, user, anchor)
        published(otherMembership, user, anchor)
        published(pending, owner, anchor)
        published(privateFollowed, owner, since.minusSeconds(1))
        published(followed, owner, anchor, "PRIVATE")
        published(followed, owner, anchor.plusNanos(1_000))

        val ids = listOf(followed, privateFollowed)
        assertThat(following(user, current, ids).map { it.id }).containsExactly(atAnchor, atLowerBoundary)
        assertThat(public(user, current, ids).map { it.id }).containsExactly(publicOther, oldFollowed)
        assertThat(public(member(), null, listOf(-1L)).map { it.id })
            .contains(atAnchor, oldFollowed, publicOther)
            .doesNotContain(atLowerBoundary)
    }

    @Test
    fun `every suppression predicate runs before limit and keyset resumes same timestamp by id`() {
        val user = member()
        val current = publicBunch(user)
        val owner = member()
        val visible = publicBunch(owner)
        val first = published(visible, owner, anchor)
        val second = published(visible, owner, anchor)
        val hidden = published(visible, owner, anchor)
        jdbc.update("INSERT INTO feed_content_hides(target_kind,target_id,reason) VALUES ('BUNDLE',?,'ops')", hidden)
        val reporterHidden = published(visible, owner, anchor)
        jdbc.update("""INSERT INTO feed_content_hides(target_kind,target_id,reporter_bunch_id,reason)
            VALUES ('BUNDLE',?,?,'REPORTED')""", reporterHidden, current)
        listOf("BLOCK_FORWARD", "BLOCK_REVERSE", "REPORT", "BUNCH_HIDE").forEach { kind ->
            val target = publicBunch(owner)
            published(target, owner, anchor)
            when (kind) {
                "BLOCK_FORWARD" -> block(current, target)
                "BLOCK_REVERSE" -> block(target, current)
                "REPORT" -> jdbc.update("""INSERT INTO bunch_profile_reports
                    (reporter_bunch_id,target_bunch_id,reported_by_member_id,reason,created_at,window_started_at)
                    VALUES (?,?,?,'SPAM',now(),now())""", current, target, user)
                else -> jdbc.update("INSERT INTO feed_content_hides(target_kind,target_id,reason) VALUES ('BUNCH',?,'ops')", target)
            }
        }
        val page = query.publicBundles(user, current, anchor, since, listOf(-1L), null, null, false, PageRequest.of(0, 1))
        assertThat(page.map { it.id }).containsExactly(second)
        val next = query.publicBundles(user, current, anchor, since, listOf(-1L), anchor, second, true, PageRequest.of(0, 2))
        assertThat(next.map { it.id }).containsExactly(first)
        assertThat(query.publicBundles(user, current, anchor, since, listOf(-1L), anchor, first, true, PageRequest.of(0, 1)))
            .isEmpty()
    }

    @Test
    fun `relationship fingerprint inputs include identity destination state and approval instant`() {
        val current = publicBunch(member())
        val target = publicBunch(member())
        follow(current, target)
        val before = query.approvedFollowing(current).single()
        assertThat(before.id).isPositive()
        assertThat(before.followedBunchId).isEqualTo(target)
        assertThat(before.status.name).isEqualTo("APPROVED")
        assertThat(before.approvedAt).isEqualTo(anchor)
        jdbc.update("UPDATE bunch_follows SET status = 'PENDING', approved_at = NULL WHERE id = ?", before.id)
        assertThat(query.approvedFollowing(current)).isEmpty()
    }

    private fun publicBunch(owner: Long) = create(owner).id.also {
        jdbc.update("UPDATE bunches SET visibility='PUBLIC' WHERE id=?", it)
    }

    private fun published(bunch: Long, owner: Long, at: Instant, visibility: String = "PUBLIC"): Long =
        jdbc.queryForObject("""INSERT INTO bundles
            (bunch_id,created_by,status,title,visibility,published_at,created_at,updated_at)
            VALUES (?,?,'PUBLISHED','feed',?,?,?,?) RETURNING id""", Long::class.java,
            bunch, owner, visibility, Timestamp.from(at), Timestamp.from(at), Timestamp.from(at))!!

    private fun follow(current: Long, target: Long, status: String = "APPROVED") {
        jdbc.update("""INSERT INTO bunch_follows
            (follower_bunch_id,followed_bunch_id,status,created_by_member_id,created_at,approved_at)
            SELECT ?,?,?,created_by,?,? FROM bunches WHERE id=?""",
            current, target, status, Timestamp.from(anchor),
            if (status == "APPROVED") Timestamp.from(anchor) else null, current)
    }

    private fun block(source: Long, target: Long) {
        jdbc.update("""INSERT INTO bunch_blocks(blocker_bunch_id,blocked_bunch_id,created_by_member_id,created_at)
            SELECT ?,?,created_by,now() FROM bunches WHERE id=?""", source, target, source)
    }

    private fun following(user: Long, current: Long?, ids: List<Long>) =
        query.following(user, current, anchor, since, ids, null, null, false, PageRequest.of(0, 50))

    private fun public(user: Long, current: Long?, ids: List<Long>) =
        query.publicBundles(user, current, anchor, since, ids, null, null, false, PageRequest.of(0, 50))
}
