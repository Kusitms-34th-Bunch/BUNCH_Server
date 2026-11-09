package com.bunch.feed

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.service.BunchSocialService
import com.bunch.feed.domain.FeedSegment
import com.bunch.feed.dto.FeedAdvertisementItem
import com.bunch.feed.dto.FeedBundleItem
import com.bunch.feed.dto.FeedFollowingCaughtUpItem
import com.bunch.feed.dto.FeedResponse
import com.bunch.feed.service.AdvertisementService
import com.bunch.feed.service.FeedService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.social.dto.CreateSocialCommentRequest
import com.bunch.social.dto.SocialContentReportRequest
import com.bunch.social.service.SocialCommentService
import com.bunch.social.service.SocialContentReportService
import com.bunch.social.service.SocialLikeService
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var feed: FeedService

    @Autowired
    private lateinit var likes: SocialLikeService

    @Autowired
    private lateinit var comments: SocialCommentService

    @Autowired
    private lateinit var reports: SocialContentReportService

    @Autowired
    private lateinit var social: BunchSocialService

    @Autowired
    private lateinit var advertisements: AdvertisementService

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    private val ownSessions = mutableListOf<Pair<String, Long>>()
    private val imageIds = mutableListOf<Long>()
    private val at = Instant.now().minusSeconds(7 * 86_400L).truncatedTo(ChronoUnit.MICROS)

    @AfterEach
    fun cleanFeedData() {
        ownSessions.forEach { (session, member) -> sessions.delete(session, member) }
        jdbc.update("DELETE FROM push_jobs")
        jdbc.update("DELETE FROM feed_content_hides")
        jdbc.update("DELETE FROM feed_advertisements")
        jdbc.update("DELETE FROM bundles")
        imageIds.forEach { id ->
            jdbc.update("UPDATE members SET profile_image_asset_id=NULL WHERE profile_image_asset_id=?", id)
            jdbc.update("UPDATE bunches SET profile_image_asset_id=NULL WHERE profile_image_asset_id=?", id)
            jdbc.update("DELETE FROM image_assets WHERE id=?", id)
        }
    }

    @Test
    fun `following then public fill one page and emit the boundary at its actual position`() {
        val user = member()
        val current = publicBunch(user)
        val owner = member()
        val followed = publicBunch(owner)
        val stranger = publicBunch(owner)
        follow(current, followed)
        val recentFollowed = published(followed, owner, at)
        val olderFollowed = published(followed, owner, at.minusSeconds(1))
        val oldFollowed = published(followed, owner, at.minusSeconds(30 * 86_400L))
        val publicNewest = published(stranger, owner, at.plusSeconds(1))

        val page = feed.get(user, 3)
        assertThat(page.bundleIds()).containsExactly(recentFollowed, olderFollowed, publicNewest)
        assertThat(page.bundles().map { it.segment })
            .containsExactly(FeedSegment.FOLLOWING, FeedSegment.FOLLOWING, FeedSegment.PUBLIC)
        assertThat(page.items.map { it.type }).containsExactly("BUNDLE", "BUNDLE", "FOLLOWING_CAUGHT_UP", "BUNDLE")
        assertThat(page.hasNext).isTrue()
        val next = feed.get(user, 50, page.nextCursor)
        assertThat(next.bundleIds()).containsExactly(oldFollowed)
        assertThat(next.items.filterIsInstance<FeedFollowingCaughtUpItem>()).isEmpty()
        assertThat(next.anchorTime).isEqualTo(page.anchorTime)
        assertThat(next.hasNext).isFalse()
        assertThat(next.endReached).isTrue()
        assertThat(next.nextCursor).isNull()
    }

    @Test
    fun `exact page boundary probes the public segment and never loses its first card`() {
        val user = member()
        val current = publicBunch(user)
        val owner = member()
        val followed = publicBunch(owner)
        val stranger = publicBunch(owner)
        follow(current, followed)
        val first = published(followed, owner)
        val second = published(stranger, owner)
        val page = feed.get(user, 1)
        assertThat(page.bundleIds()).containsExactly(first)
        assertThat(page.items.last()).isInstanceOf(FeedFollowingCaughtUpItem::class.java)
        assertThat(page.hasNext).isTrue()
        val next = feed.get(user, 1, page.nextCursor)
        assertThat(next.bundleIds()).containsExactly(second)
        assertThat(next.endReached).isTrue()
        jdbc.update("DELETE FROM bundles WHERE id=?", second)
        val onlyFollowing = feed.get(user, 1)
        assertThat(onlyFollowing.hasNext).isFalse()
        assertThat(onlyFollowing.nextCursor).isNull()
    }

    @Test
    fun `empty following skips caught up and completely empty feed reaches the end without ads`() {
        val user = member()
        val current = publicBunch(user)
        val owner = member()
        val followed = create(owner).id
        follow(current, followed)
        published(followed, owner, visibility = "PRIVATE")
        advertisement()
        val empty = feed.get(user)
        assertThat(empty.items).isEmpty()
        assertThat(empty.bundleCount).isZero()
        assertThat(empty.hasNext).isFalse()
        assertThat(empty.endReached).isTrue()
        assertThat(empty.nextCursor).isNull()
        val public = published(publicBunch(owner), owner)
        val page = feed.get(user)
        assertThat(page.bundleIds()).containsExactly(public)
        assertThat(page.items).hasSize(1)
    }

    @Test
    fun `all active memberships are excluded and zero memberships read public without actions`() {
        val user = member()
        val current = publicBunch(user)
        val other = publicBunch(user)
        published(current, user)
        published(other, user)
        val owner = member()
        val target = publicBunch(owner)
        val visible = published(target, owner)
        val private = published(target, owner, visibility = "PRIVATE")
        val privateBunch = published(create(owner).id, owner)
        val page = feed.get(user)
        assertThat(page.bundleIds()).containsExactly(visible)
        assertThat(page.canInteract).isTrue()
        val visitor = member()
        val guest = feed.get(visitor)
        assertThat(guest.currentBunchId).isNull()
        assertThat(guest.canInteract).isFalse()
        assertThat(guest.bundleIds()).contains(visible).doesNotContain(private, privateBunch)
        assertThat(guest.bundles()).allMatch { it.segment == FeedSegment.PUBLIC }
        assertThat(guest.items.filterIsInstance<FeedFollowingCaughtUpItem>()).isEmpty()
    }

    @Test
    fun `publication ties traverse by descending id and new publications wait until refresh`() {
        val owner = member()
        val target = publicBunch(owner)
        val ids = (1..7).map { published(target, owner) }.reversed()
        val visitor = member()
        val first = feed.get(visitor, 2)
        val new = published(target, owner, first.anchorTime.plusNanos(1_000))
        val all = first.bundleIds().toMutableList()
        var page = first
        while (page.hasNext) {
            page = feed.get(visitor, 2, page.nextCursor)
            assertThat(page.anchorTime).isEqualTo(first.anchorTime)
            all += page.bundleIds()
        }
        assertThat(all).containsExactlyElementsOf(ids)
        assertThat(feed.get(visitor).bundleIds()).contains(new)
    }

    @Test
    fun `public page refills before limit after block report ops private and deletion changes`() {
        val user = member()
        val current = publicBunch(user)
        val owner = member()
        val target = publicBunch(owner)
        val surviving = published(target, owner)
        val deleted = published(target, owner)
        val private = published(target, owner)
        val ops = published(target, owner)
        val reported = published(target, owner)
        val blockedTarget = publicBunch(owner)
        val blocked = published(blockedTarget, owner)
        val first = published(target, owner)
        val page = feed.get(user, 1)
        assertThat(page.bundleIds()).containsExactly(first)
        jdbc.update("DELETE FROM bundles WHERE id=?", deleted)
        jdbc.update("UPDATE bundles SET visibility='PRIVATE' WHERE id=?", private)
        hide("BUNDLE", ops)
        reports.bundle(user, reported, SocialContentReportRequest(BunchReportReason.SPAM))
        block(blockedTarget, current)
        val next = feed.get(user, 20, page.nextCursor)
        assertThat(next.bundleIds()).containsExactly(surviving)
        assertThat(next.bundleIds()).doesNotContain(blocked)
        assertThat(next.endReached).isTrue()
        hide("BUNDLE", surviving)
        val nowEmpty = feed.get(user, 20, page.nextCursor)
        assertThat(nowEmpty.items).isEmpty()
        assertThat(nowEmpty.hasNext).isFalse()
        assertThat(nowEmpty.nextCursor).isNull()
    }

    @Test
    fun `remaining hidden following transitions to public in the same request`() {
        val user = member()
        val current = publicBunch(user)
        val owner = member()
        val followed = publicBunch(owner)
        follow(current, followed)
        val hidden = published(followed, owner)
        published(followed, owner)
        val stranger = published(publicBunch(owner), owner)
        val page = feed.get(user, 1)
        hide("BUNDLE", hidden)
        val next = feed.get(user, 1, page.nextCursor)
        assertThat(next.items.map { it.type }).containsExactly("FOLLOWING_CAUGHT_UP", "BUNDLE")
        assertThat(next.bundleIds()).containsExactly(stranger)
        assertThat(next.endReached).isTrue()
    }

    @Test
    fun `current switching away and back and active membership changes invalidate the cursor`() {
        val user = member()
        val first = publicBunch(user)
        val second = publicBunch(user)
        bunches.updateLastUsed(user, first)
        val owner = member()
        val target = publicBunch(owner)
        repeat(3) { published(target, owner) }
        val cursor = feed.get(user, 1).nextCursor!!
        bunches.updateLastUsed(user, second)
        assertError(ErrorCode.FEED_CURSOR_CONTEXT_CHANGED) { feed.get(user, 1, cursor) }
        bunches.updateLastUsed(user, first)
        assertError(ErrorCode.FEED_CURSOR_CONTEXT_CHANGED) { feed.get(user, 1, cursor) }
        val refreshed = feed.get(user, 1).nextCursor!!
        jdbc.update("UPDATE bunch_memberships SET status='LEFT' WHERE member_id=? AND bunch_id=?", user, second)
        assertError(ErrorCode.FEED_CURSOR_CONTEXT_CHANGED) { feed.get(user, 1, refreshed) }
        assertError(ErrorCode.INVALID_INPUT) { feed.get(member(), 1, refreshed) }
    }

    @Test
    fun `follow cancellation approval timestamp and recreated relationship require refresh`() {
        val user = member()
        val current = publicBunch(user)
        val owner = member()
        val target = publicBunch(owner)
        repeat(3) { published(target, owner) }
        follow(current, target)
        val cursor = feed.get(user, 1).nextCursor!!
        jdbc.update("UPDATE bunch_follows SET approved_at=approved_at+interval '1 second' WHERE follower_bunch_id=?", current)
        assertError(ErrorCode.FEED_CURSOR_CONTEXT_CHANGED) { feed.get(user, 1, cursor) }
        val changed = feed.get(user, 1).nextCursor!!
        jdbc.update("DELETE FROM bunch_follows WHERE follower_bunch_id=?", current)
        follow(current, target)
        assertError(ErrorCode.FEED_CURSOR_CONTEXT_CHANGED) { feed.get(user, 1, changed) }
        val recreated = feed.get(user, 1).nextCursor!!
        jdbc.update("DELETE FROM bunch_follows WHERE follower_bunch_id=?", current)
        assertError(ErrorCode.FEED_CURSOR_CONTEXT_CHANGED) { feed.get(user, 1, recreated) }
        assertThat(feed.get(user).bundles()).allMatch { it.segment == FeedSegment.PUBLIC }
    }

    @Test
    fun `card batches preserve photo place and published participant order and departed attribution`() {
        val user = member()
        val owner = member()
        val target = publicBunch(owner)
        val departed = member()
        val withdrawn = member()
        join(departed, issue(owner, target))
        join(withdrawn, issue(owner, target))
        val bundle = published(target, owner)
        jdbc.update("UPDATE bundles SET title='제목', bundle_date='2026-10-30' WHERE id=?", bundle)
        val cover = image("cover")
        val later = image("later")
        photo(bundle, later, 1, owner)
        photo(bundle, cover, 0, owner)
        val avatar = image("avatar")
        jdbc.update("UPDATE members SET profile_image_asset_id=? WHERE id=?", avatar, owner)
        jdbc.update("UPDATE bunches SET profile_image_asset_id=? WHERE id=?", avatar, target)
        listOf(owner, departed, withdrawn).forEachIndexed { index, member ->
            jdbc.update("INSERT INTO bundle_participants(bundle_id,member_id,joined_at) VALUES (?,?,?)",
                bundle, member, Timestamp.from(at.plusSeconds(index.toLong())))
        }
        jdbc.update("UPDATE bunch_memberships SET status='LEFT' WHERE bunch_id=? AND member_id=?", target, departed)
        jdbc.update("UPDATE members SET account_status='WITHDRAWN' WHERE id=?", withdrawn)
        // A later member is not added to the publication snapshot.
        join(member(), issue(owner, target))
        listOf(1, 0).forEach { position ->
            jdbc.update("""INSERT INTO bundle_locations(bundle_id,provider,external_id,name,address,position)
                VALUES (?,'kakao',?,?,?,?)""", bundle, "place-$position", "장소$position", "주소$position", position)
        }
        val card = feed.get(user).bundles().single().bundle
        assertThat(card.id).isEqualTo(bundle)
        assertThat(card.title).isEqualTo("제목")
        assertThat(card.date.toString()).isEqualTo("2026-10-30")
        assertThat(card.photos.map { it.imageUrl }).containsExactly("https://example.com/cover.jpg", "https://example.com/later.jpg")
        assertThat(card.coverImageUrl).isEqualTo(card.photos.first().imageUrl)
        assertThat(card.locations.map { it.position }).containsExactly(0, 1)
        assertThat(card.participants.map { it.memberId }).containsExactly(owner, departed, withdrawn)
        assertThat(card.participants.map { it.profileAvailable }).containsExactly(true, false, false)
        assertThat(card.participants.drop(1).map { it.nickname }).containsExactly("작성자", "작성자")
        assertThat(card.participants.drop(1).map { it.imageUrl }).containsOnlyNulls()
        assertThat(card.bunch.imageUrl).isEqualTo("https://example.com/avatar.jpg")
        jdbc.update("UPDATE bunch_memberships SET status='ACTIVE' WHERE bunch_id=? AND member_id=?", target, departed)
        assertThat(feed.get(user).bundles().single().bundle.participants[1].profileAvailable).isTrue()
    }

    @Test
    fun `actual reaction batches include deleted slots and exclude blocked reported and moderated actors and comments`() {
        val user = member()
        val current = publicBunch(user)
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        val actors = (1..4).map { member().let { id -> id to publicBunch(id) } }
        likes.like(user, bundle)
        actors.forEach { likes.like(it.first, bundle) }
        val root = comment(user, bundle)
        comment(user, bundle, root)
        val deleted = comment(user, bundle)
        comments.delete(user, bundle, deleted)
        val visiblePrivate = actors[0]
        jdbc.update("UPDATE bunches SET visibility='PRIVATE' WHERE id=?", visiblePrivate.second)
        comment(visiblePrivate.first, bundle)
        val blockedRoot = comment(actors[1].first, bundle)
        comment(user, bundle, blockedRoot)
        comment(actors[2].first, bundle)
        comment(actors[3].first, bundle)
        val hiddenRoot = comment(visiblePrivate.first, bundle)
        comment(user, bundle, hiddenRoot)
        block(current, actors[1].second)
        social.report(user, current, BunchReportRequest(actors[2].second, BunchReportReason.SPAM, null))
        hide("BUNCH", actors[3].second)
        reports.comment(user, bundle, hiddenRoot, SocialContentReportRequest(BunchReportReason.SPAM))
        val card = feed.get(user).bundles().single().bundle
        assertThat(card.likeCount).isEqualTo(2)
        assertThat(card.likedByCurrentBunch).isTrue()
        assertThat(card.commentCount).isEqualTo(4)
        assertThat(card.commentCount).isEqualTo(comments.counts(user, listOf(bundle)).getValue(bundle))
        assertThat(card.likeCount).isEqualTo(likes.summaries(user, listOf(bundle)).getValue(bundle).likeCount)
    }

    @Test
    fun `six bundle ad cadence continues across page and section boundaries and skips a repeated singleton`() {
        val user = member()
        val current = publicBunch(user)
        val owner = member()
        val followed = publicBunch(owner)
        val target = publicBunch(owner)
        follow(current, followed)
        repeat(5) { published(followed, owner) }
        repeat(8) { published(target, owner) }
        val ad = advertisement()
        val first = feed.get(user, 4)
        assertThat(first.items.filterIsInstance<FeedAdvertisementItem>()).isEmpty()
        val second = feed.get(user, 4, first.nextCursor)
        assertThat(second.items.map { it.type })
            .containsExactly("BUNDLE", "FOLLOWING_CAUGHT_UP", "BUNDLE", "ADVERTISEMENT", "BUNDLE", "BUNDLE")
        assertThat(second.items.filterIsInstance<FeedAdvertisementItem>().single().advertisement.id).isEqualTo(ad)
        val third = feed.get(user, 5, second.nextCursor)
        assertThat(third.bundleCount).isEqualTo(5)
        assertThat(third.items.filterIsInstance<FeedAdvertisementItem>()).isEmpty()
        assertThat(third.endReached).isTrue()
        val alternative = advertisement()
        val replay = feed.get(user, 5, second.nextCursor)
        assertThat(replay.bundleIds()).containsExactlyElementsOf(third.bundleIds())
        assertThat(replay.items.filterIsInstance<FeedAdvertisementItem>().single().advertisement.id).isEqualTo(alternative)
        assertThat(replay.bundleCount).isEqualTo(5)
    }

    @Test
    fun `page replay freshly selects eligible advertisements without changing bundles`() {
        val owner = member()
        val target = publicBunch(owner)
        repeat(13) { published(target, owner) }
        val visitor = member()
        val firstAd = advertisement()
        val first = feed.get(visitor, 5)
        val page = feed.get(visitor, 6, first.nextCursor)
        assertThat(page.items.filterIsInstance<FeedAdvertisementItem>().single().advertisement.id).isEqualTo(firstAd)
        advertisements.hide(visitor, firstAd)
        val replacement = advertisement()
        val replay = feed.get(visitor, 6, first.nextCursor)
        assertThat(replay.bundleIds()).containsExactlyElementsOf(page.bundleIds())
        assertThat(replay.items.filterIsInstance<FeedAdvertisementItem>().single().advertisement.id).isEqualTo(replacement)
        assertThat(replay.items.filterIsInstance<FeedAdvertisementItem>().single().advertisement.canPin).isFalse()
    }

    @Test
    fun `fifty bundle page places eight real ads without counting them in size`() {
        val owner = member()
        val target = publicBunch(owner)
        repeat(50) { published(target, owner) }
        val eligible = setOf(advertisement(), advertisement())
        val page = feed.get(member(), 50)
        assertThat(page.bundleCount).isEqualTo(50)
        assertThat(page.items).hasSize(58)
        val ads = page.items.filterIsInstance<FeedAdvertisementItem>()
        assertThat(ads).hasSize(8)
        assertThat(ads.map { it.advertisement.id }).allMatch { it in eligible }
        assertThat(ads.map { it.advertisement.id }.zipWithNext()).allMatch { it.first != it.second }
        var bundleCount = 0
        page.items.forEach { item ->
            when (item) {
                is FeedBundleItem -> bundleCount++
                is FeedAdvertisementItem -> assertThat(bundleCount % 6).isZero()
                else -> error("No following marker in a public-only feed")
            }
        }
        assertThat(page.endReached).isTrue()
    }

    @Test
    fun `page size defaults to twenty caps at fifty and invalid inputs and unfinished accounts fail`() {
        val visitor = member()
        val owner = member()
        val target = publicBunch(owner)
        repeat(52) { published(target, owner) }
        assertThat(feed.get(visitor).bundleCount).isEqualTo(20)
        assertThat(feed.get(visitor, 50).bundleCount).isEqualTo(50)
        listOf(0, -1, 51).forEach { size -> assertError(ErrorCode.INVALID_INPUT) { feed.get(visitor, size) } }
        assertError(ErrorCode.INVALID_INPUT) { feed.get(visitor, cursor = "garbled") }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { feed.get(member(false)) }
        jdbc.update("UPDATE members SET account_status='WITHDRAWN' WHERE id=?", visitor)
        assertError(ErrorCode.UNAUTHORIZED) { feed.get(visitor) }
    }

    @Test
    fun `http authenticates sessions serializes ordered typed items and returns context changed as 409`() {
        mvc.perform(get("/api/v1/feed")).andExpect(status().isUnauthorized)
        val user = member()
        val current = publicBunch(user)
        val owner = member()
        val target = publicBunch(owner)
        repeat(2) { published(target, owner) }
        val bearer = bearer(user)
        mvc.perform(get("/api/v1/feed").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.bundleCount").value(2))
            .andExpect(jsonPath("$.data.items[0].type").value("BUNDLE"))
            .andExpect(jsonPath("$.data.items[0].segment").value("PUBLIC"))
            .andExpect(jsonPath("$.data.endReached").value(true))
        listOf("0", "51", "no").forEach { size ->
            mvc.perform(get("/api/v1/feed").header("Authorization", bearer).param("size", size))
                .andExpect(status().isBadRequest)
        }
        val cursor = feed.get(user, 1).nextCursor!!
        follow(current, target)
        mvc.perform(get("/api/v1/feed").header("Authorization", bearer).param("cursor", cursor))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("FEED_001"))
    }

    @Test
    fun `card batch query count stays bounded as cards and individual profiles increase`() {
        val visitor = member()
        repeat(20) {
            val owner = member()
            val target = publicBunch(owner)
            val bundle = published(target, owner)
            val profile = image("profile-$it")
            jdbc.update("UPDATE members SET profile_image_asset_id=? WHERE id=?", profile, owner)
            jdbc.update("UPDATE bunches SET profile_image_asset_id=? WHERE id=?", profile, target)
            photo(bundle, profile, 0, owner)
            jdbc.update("INSERT INTO bundle_participants(bundle_id,member_id,joined_at) VALUES (?,?,?)",
                bundle, owner, Timestamp.from(at))
        }
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.clear()
        feed.get(visitor, 1)
        val one = statistics.prepareStatementCount
        statistics.clear()
        assertThat(feed.get(visitor, 20).bundleCount).isEqualTo(20)
        val twenty = statistics.prepareStatementCount
        assertThat(twenty).isLessThanOrEqualTo(one + 2)
        assertThat(twenty).isLessThanOrEqualTo(40)
    }

    private fun FeedResponse.bundles() = items.filterIsInstance<FeedBundleItem>()

    private fun FeedResponse.bundleIds() = bundles().map { it.bundle.id }

    private fun publicBunch(owner: Long) = create(owner).id.also {
        jdbc.update("UPDATE bunches SET visibility='PUBLIC' WHERE id=?", it)
    }

    private fun published(bunch: Long, owner: Long, whenPublished: Instant = at, visibility: String = "PUBLIC"): Long =
        jdbc.queryForObject("""INSERT INTO bundles
            (bunch_id,created_by,status,title,visibility,published_at,created_at,updated_at)
            VALUES (?,?,'PUBLISHED','feed',?,?,?,?) RETURNING id""", Long::class.java,
            bunch, owner, visibility, Timestamp.from(whenPublished), Timestamp.from(whenPublished), Timestamp.from(whenPublished))!!

    private fun follow(current: Long, target: Long) {
        jdbc.update("""INSERT INTO bunch_follows
            (follower_bunch_id,followed_bunch_id,status,created_by_member_id,created_at,approved_at)
            SELECT ?,?,'APPROVED',created_by,now(),now() FROM bunches WHERE id=?""", current, target, current)
    }

    private fun hide(kind: String, target: Long, reporter: Long? = null) {
        jdbc.update("""INSERT INTO feed_content_hides(target_kind,target_id,reporter_bunch_id,reason)
            VALUES (?,?,?,'test hide')""", kind, target, reporter)
    }

    private fun block(source: Long, target: Long) {
        jdbc.update("""INSERT INTO bunch_blocks(blocker_bunch_id,blocked_bunch_id,created_by_member_id,created_at)
            SELECT ?,?,created_by,now() FROM bunches WHERE id=?""", source, target, source)
    }

    private fun image(name: String): Long = jdbc.queryForObject("""INSERT INTO image_assets(public_id,url,created_at)
        VALUES (?,?,now()) RETURNING id""", Long::class.java, "feed/${UUID.randomUUID()}", "https://example.com/$name.jpg")!!
        .also { imageIds += it }

    private fun photo(bundle: Long, image: Long, position: Int, owner: Long) {
        jdbc.update("""INSERT INTO bundle_photos(bundle_id,image_asset_id,position,added_by,added_at)
            VALUES (?,?,?,?,now())""", bundle, image, position, owner)
    }

    private fun comment(user: Long, bundle: Long, parent: Long? = null): Long =
        comments.create(user, bundle, UUID.randomUUID(), CreateSocialCommentRequest("댓글", parent)).id

    private fun advertisement(): Long = jdbc.queryForObject("""INSERT INTO feed_advertisements
        (name,introduction,photo_url,place_provider,place_external_id,place_name,place_address)
        VALUES ('추천','소개','https://example.com/ad.jpg','kakao',?,'장소','주소') RETURNING id""",
        Long::class.java, UUID.randomUUID().toString())!!

    private fun bearer(user: Long): String {
        val pair = tokens.generateToken(user, "ROLE_USER")
        val session = tokens.getSessionId(pair.accessToken)
        sessions.save(session, user, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        ownSessions += session to user
        return "Bearer ${pair.accessToken}"
    }
}
