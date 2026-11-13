package com.bunch.kok

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.dto.FollowDecision
import com.bunch.bunch.service.BunchPublicService
import com.bunch.bunch.service.BunchSocialService
import com.bunch.feed.domain.FeedContentHideRepository
import com.bunch.feed.domain.FeedContentKind
import com.bunch.global.exception.ErrorCode
import com.bunch.kok.dto.KokCardResponse
import com.bunch.kok.service.KokService
import org.assertj.core.api.Assertions.assertThat
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
class KokListIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var pins: KokService

    @Autowired
    private lateinit var social: BunchSocialService

    @Autowired
    private lateinit var content: BunchPublicService

    @Autowired
    private lateinit var hides: FeedContentHideRepository

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    private val imageIds = mutableListOf<Long>()

    @AfterEach
    fun cleanImages() {
        imageIds.forEach { image ->
            jdbc.update("UPDATE bunches SET profile_image_asset_id = NULL WHERE profile_image_asset_id = ?", image)
            jdbc.update("UPDATE members SET profile_image_asset_id = NULL WHERE profile_image_asset_id = ?", image)
            jdbc.update("DELETE FROM image_assets WHERE id = ?", image)
        }
        imageIds.clear()
    }

    @Test
    fun `source and member profiles are batched even when every source has a distinct image`() {
        val f = fixture()
        val firstImage = image(f.sourceBunch)
        jdbc.update("UPDATE members SET profile_image_asset_id = ? WHERE id = ?", firstImage, f.actor)
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        val wasEnabled = statistics.isStatisticsEnabled
        try {
            statistics.isStatisticsEnabled = true
            statistics.clear()
            val first = pins.list(f.actor)
            val oneSourceQueries = statistics.prepareStatementCount
            assertThat(first.members.single().imageUrl).isEqualTo("https://example.invalid/$firstImage.jpg")
            repeat(4) { index ->
                val owner = member()
                val source = create(owner).id
                jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", source)
                image(source)
                val bundle = bundle(source, owner)
                val location = location(bundle, "source-$index")
                pins.pinBundleLocation(f.actor, bundle, location)
            }
            statistics.clear()
            val batch = pins.list(f.actor)
            assertThat(batch.items).hasSize(5)
            assertThat(batch.items.map { it.sourceAccessible }).containsOnly(true)
            assertThat(statistics.prepareStatementCount).isEqualTo(oneSourceQueries)
        } finally {
            statistics.clear()
            statistics.isStatisticsEnabled = wasEnabled
        }
    }

    @Test
    fun `counts retain departed withdrawn and unknown registrants while chips reuse management order`() {
        val actor = member()
        val bunch = create(actor).id
        val coOwner = member()
        val zero = member()
        val left = member()
        val removed = member()
        val withdrawn = member()
        listOf(coOwner, zero, left, removed, withdrawn).forEach { join(it, issue(actor, bunch)) }
        jdbc.update("UPDATE members SET nickname = 'ZOwner' WHERE id = ?", actor)
        jdbc.update("UPDATE members SET nickname = 'ZCoowner' WHERE id = ?", coOwner)
        jdbc.update("UPDATE members SET nickname = 'AZero' WHERE id = ?", zero)
        jdbc.update("UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE member_id = ?", coOwner)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", left)
        jdbc.update("UPDATE bunch_memberships SET status = 'REMOVED' WHERE member_id = ?", removed)
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", withdrawn)
        listOf(actor, coOwner, coOwner, left, removed, withdrawn, null).forEachIndexed { index, registrant ->
            pin(bunch, registrant, "place-$index")
        }
        listOf(actor, coOwner, zero).forEach { viewer ->
            val result = pins.list(viewer)
            assertThat(result.totalCount).isEqualTo(7)
            assertThat(result.items).hasSize(7)
            assertThat(result.members.map { it.memberId }).containsExactly(actor, coOwner, zero)
            assertThat(result.members.map { it.count }).containsExactly(1L, 2L, 0L)
            assertThat(result.members.map { it.nickname }).containsExactly("ZOwner", "ZCoowner", "AZero")
            assertThat(result.members.map { it.membershipId }).doesNotHaveDuplicates()
            assertThat(result.members.map { it.imageUrl }).containsOnlyNulls()
        }
        val filtered = pins.list(actor, memberId = coOwner)
        assertThat(filtered.selectedMemberId).isEqualTo(coOwner)
        assertThat(filtered.totalCount).isEqualTo(7)
        assertThat(filtered.items.map { it.pinnedByMemberId }).containsExactly(coOwner, coOwner)
        assertThat(pins.list(actor, memberId = zero).items).isEmpty()
    }

    @Test
    fun `keyset retains unavailable pins handles ties deleted boundary and exact final page`() {
        val actor = member()
        val bunch = create(actor).id
        val ids = (1..5).map { pin(bunch, actor, "place-$it") }
        val first = pins.list(actor, size = 2)
        assertThat(first.items.map { it.id }).containsExactly(ids[4], ids[3])
        assertThat(first.items.map { it.sourceAccessible }).containsOnly(false)
        assertThat(first.nextCursor).isNotNull()
        pins.unpin(actor, ids[3])
        val newPin = pin(bunch, actor, "new", time = Instant.parse("2026-11-02T00:00:00Z"))
        val second = pins.list(actor, cursor = first.nextCursor, size = 2)
        assertThat(second.items.map { it.id }).containsExactly(ids[2], ids[1])
        assertThat(second.totalCount).isEqualTo(5)
        val last = pins.list(actor, cursor = second.nextCursor, size = 1)
        assertThat(last.items.map { it.id }).containsExactly(ids[0])
        assertThat(last.nextCursor).isNull()
        assertThat(pins.list(actor, size = 50).items.first().id).isEqualTo(newPin)
    }

    @Test
    fun `member keyset excludes interleaved other registrants and rejects another viewers cursor`() {
        val actor = member()
        val bunch = create(actor).id
        val colleague = member()
        join(colleague, issue(actor, bunch))
        val firstId = pin(bunch, colleague, "first")
        pin(bunch, actor, "other")
        val lastId = pin(bunch, colleague, "last")
        val first = pins.list(actor, memberId = colleague, size = 1)
        assertThat(first.items.single().id).isEqualTo(lastId)
        val next = pins.list(actor, memberId = colleague, cursor = first.nextCursor, size = 1)
        assertThat(next.items.single().id).isEqualTo(firstId)
        assertThat(next.nextCursor).isNull()
        assertError(ErrorCode.INVALID_INPUT) {
            pins.list(colleague, memberId = colleague, cursor = first.nextCursor)
        }
    }

    @Test
    fun `departed selected member resets chip and cursor to all from the beginning`() {
        val actor = member()
        val bunch = create(actor).id
        val colleague = member()
        join(colleague, issue(actor, bunch))
        pin(bunch, colleague, "first")
        pin(bunch, colleague, "second")
        val newest = pin(bunch, actor, "newest")
        val selected = pins.list(actor, memberId = colleague, size = 1)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", colleague)
        val refreshed = pins.list(actor, memberId = colleague, cursor = selected.nextCursor, size = 1)
        assertThat(refreshed.selectedMemberId).isNull()
        assertThat(refreshed.items.single().id).isEqualTo(newest)
        assertThat(refreshed.totalCount).isEqualTo(3)
        assertThat(refreshed.members.map { it.memberId }).containsExactly(actor)
        join(colleague, issue(actor, bunch))
        assertThat(pins.list(actor, memberId = colleague).items).hasSize(2)
        assertThat(pins.list(actor).members.first { it.memberId == colleague }.count).isEqualTo(2)
    }

    @Test
    fun `bundle privacy private bunch approval and follow cancellation change only source visibility`() {
        val f = fixture()
        assertVisible(f)
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", f.bundle)
        assertHidden(f)
        jdbc.update("UPDATE bundles SET visibility = 'PUBLIC' WHERE id = ?", f.bundle)
        assertVisible(f)
        jdbc.update("UPDATE bunches SET visibility = 'PRIVATE' WHERE id = ?", f.sourceBunch)
        assertHidden(f)
        val follow = social.follow(f.actor, f.bunch, f.sourceBunch).followId!!
        assertHidden(f)
        social.decide(f.owner, f.sourceBunch, follow, FollowDecision.APPROVE)
        assertVisible(f)
        social.cancel(f.actor, f.bunch, f.sourceBunch, follow)
        assertHidden(f)
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", f.sourceBunch)
        assertVisible(f)
    }

    @Test
    fun `bilateral block and reporter or global content hiding never leak source metadata`() {
        val f = fixture()
        social.block(f.owner, f.sourceBunch, f.bunch)
        assertHidden(f)
        jdbc.update("DELETE FROM bunch_blocks")
        assertVisible(f)
        social.block(f.actor, f.bunch, f.sourceBunch)
        assertHidden(f)
        jdbc.update("DELETE FROM bunch_blocks")
        TransactionTemplate(transactions).execute {
            hides.hideForReporter(FeedContentKind.BUNDLE, f.bundle, f.bunch)
        }
        assertHidden(f)
        jdbc.update("DELETE FROM feed_content_hides")
        assertVisible(f)
        jdbc.update(
            """INSERT INTO feed_content_hides(target_kind, target_id, reason)
                VALUES ('BUNCH', ?, 'moderated')""", f.sourceBunch
        )
        assertHidden(f)
        jdbc.update("DELETE FROM feed_content_hides")
        assertVisible(f)
    }

    @Test
    fun `active removal notice hides public source until expiry just as detail does`() {
        val f = fixture()
        jdbc.update(
            """INSERT INTO bunch_access_notices(recipient_member_id, bunch_id, membership_id, bunch_name,
                character_type, reason, occurred_at, expires_at)
                VALUES (?, ?, ?, 'former source', 'TYPE_2', 'REMOVED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day')""",
            f.actor, f.sourceBunch, Long.MAX_VALUE
        )
        assertHidden(f)
        assertError(ErrorCode.BUNCH_ACCESS_UNAVAILABLE) { content.bundle(f.actor, f.sourceBunch, f.bundle) }
        jdbc.update(
            """UPDATE bunch_access_notices SET occurred_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE recipient_member_id = ?""", f.actor
        )
        assertVisible(f)
    }

    @Test
    fun `source bundle and source bunch deletion preserve place snapshots and null provenance`() {
        val f = fixture()
        jdbc.update("DELETE FROM bundles WHERE id = ?", f.bundle)
        assertHidden(f)
        val secondBundle = bundle(f.sourceBunch, f.owner)
        val secondLocation = location(secondBundle, "another")
        val secondPin = pins.pinBundleLocation(f.actor, secondBundle, secondLocation)
        jdbc.update("DELETE FROM bunches WHERE id = ?", f.sourceBunch)
        val remaining = pins.list(f.actor)
        assertThat(remaining.totalCount).isEqualTo(2)
        assertThat(remaining.items.map { it.id }).containsExactly(secondPin.id, f.pinId)
        remaining.items.forEach(::assertNoSource)
        assertThat(remaining.items.map { it.name }).containsOnly("original place")
    }

    @Test
    fun `own private published source stays accessible and advertisement keeps distinct source kind`() {
        val f = fixture()
        val ownBundle = bundle(f.bunch, f.actor, visibility = "PRIVATE")
        val ownLocation = location(ownBundle, "own")
        val own = pins.pinBundleLocation(f.actor, ownBundle, ownLocation)
        val advertisement = pin(f.bunch, f.actor, "ad", sourceType = "ADVERTISEMENT")
        val result = pins.list(f.actor)
        assertThat(result.items.first { it.id == own.id }.sourceAccessible).isTrue()
        val ad = result.items.first { it.id == advertisement }
        assertThat(ad.sourceType.name).isEqualTo("ADVERTISEMENT")
        assertNoSource(ad)
    }

    @Test
    fun `duplicate source keeps first registrant and switching current bunch cannot reuse old cursor`() {
        val f = fixture()
        val colleague = member()
        join(colleague, issue(f.actor, f.bunch))
        val otherBundle = bundle(f.sourceBunch, f.owner)
        val otherLocation = location(otherBundle, "external")
        assertThat(pins.pinBundleLocation(colleague, otherBundle, otherLocation).id).isEqualTo(f.pinId)
        assertThat(pins.list(f.actor, memberId = colleague).items).isEmpty()
        assertThat(pins.list(f.actor).items.single().sourceBundleId).isEqualTo(f.bundle)
        pin(f.bunch, f.actor, "second")
        val cursor = pins.list(f.actor, size = 1).nextCursor!!
        val newBunch = create(f.actor).id
        assertThat(pins.list(f.actor).bunchId).isEqualTo(newBunch)
        assertThat(pins.list(f.actor).items).isEmpty()
        assertError(ErrorCode.INVALID_INPUT) { pins.list(f.actor, cursor = cursor) }
        assertThat(pins.list(colleague).totalCount).isEqualTo(2)
    }

    private fun image(bunch: Long): Long {
        val image = jdbc.queryForObject(
            """INSERT INTO image_assets(public_id, url, created_at)
                VALUES (?, 'placeholder', CURRENT_TIMESTAMP) RETURNING id""",
            Long::class.java, "pin-list-$bunch"
        )!!
        jdbc.update("UPDATE image_assets SET url = ? WHERE id = ?", "https://example.invalid/$image.jpg", image)
        jdbc.update("UPDATE bunches SET profile_image_asset_id = ? WHERE id = ?", image, bunch)
        imageIds.add(image)
        return image
    }

    private fun fixture(): Fixture {
        val actor = member()
        val bunch = create(actor).id
        val owner = member()
        val source = create(owner).id
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", source)
        val bundle = bundle(source, owner)
        val location = location(bundle, "external")
        val pin = pins.pinBundleLocation(actor, bundle, location)
        return Fixture(actor, bunch, owner, source, bundle, pin.id)
    }

    private fun assertVisible(f: Fixture) {
        val result = pins.list(f.actor)
        assertThat(result.totalCount).isEqualTo(1)
        val card = result.items.single()
        assertThat(card.id).isEqualTo(f.pinId)
        assertThat(card.name).isEqualTo("original place")
        assertThat(card.address).isEqualTo("original address")
        assertThat(card.sourceAccessible).isTrue()
        assertThat(card.sourceBundleId).isEqualTo(f.bundle)
        assertThat(card.sourceBunchId).isEqualTo(f.sourceBunch)
        assertThat(card.sourceBundleTitle).isEqualTo("published")
        assertThat(card.sourceBunchName).isNotBlank()
    }

    private fun assertHidden(f: Fixture) {
        val result = pins.list(f.actor)
        assertThat(result.totalCount).isEqualTo(1)
        val card = result.items.single()
        assertThat(card.id).isEqualTo(f.pinId)
        assertThat(card.name).isEqualTo("original place")
        assertThat(card.address).isEqualTo("original address")
        assertNoSource(card)
    }

    private fun assertNoSource(card: KokCardResponse) {
        assertThat(card.sourceAccessible).isFalse()
        assertThat(card.sourceBundleId).isNull()
        assertThat(card.sourceBunchId).isNull()
        assertThat(card.sourceBunchName).isNull()
        assertThat(card.sourceBundleTitle).isNull()
    }

    private fun bundle(bunch: Long, owner: Long, visibility: String = "PUBLIC"): Long = jdbc.queryForObject(
        """INSERT INTO bundles(bunch_id, created_by, status, title, visibility, created_at, updated_at, published_at)
            VALUES (?, ?, 'PUBLISHED', 'published', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id""", Long::class.java, bunch, owner, visibility
    )!!

    private fun location(bundle: Long, externalId: String): Long = jdbc.queryForObject(
        """INSERT INTO bundle_locations(bundle_id, provider, external_id, name, address, position)
            VALUES (?, 'kakao', ?, 'original place', 'original address', 0) RETURNING id""",
        Long::class.java, bundle, externalId
    )!!

    private fun pin(
        bunch: Long,
        registrant: Long?,
        externalId: String,
        time: Instant = Instant.parse("2026-11-01T00:00:00Z"),
        sourceType: String = "BUNDLE"
    ): Long = jdbc.queryForObject(
        """INSERT INTO pinned_places(bunch_id, provider, external_id, name, address,
            source_type, pinned_by_member_id, pinned_at)
            VALUES (?, 'kakao', ?, 'fixture place', 'fixture address', ?, ?, ?) RETURNING id""",
        Long::class.java, bunch, externalId, sourceType, registrant, java.sql.Timestamp.from(time)
    )!!

    private data class Fixture(
        val actor: Long,
        val bunch: Long,
        val owner: Long,
        val sourceBunch: Long,
        val bundle: Long,
        val pinId: Long
    )
}
