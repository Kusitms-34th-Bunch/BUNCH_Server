package com.bunch.place

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.dto.FollowDecision
import com.bunch.bunch.service.BunchSocialService
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleLocationInput
import com.bunch.bundle.service.BundleLocationService
import com.bunch.bundle.service.BundleLocationsRemovedEvent
import com.bunch.bundle.service.BundleService
import com.bunch.feed.domain.FeedContentHideRepository
import com.bunch.feed.domain.FeedContentKind
import com.bunch.global.exception.ErrorCode
import com.bunch.place.domain.PinnedPlaceRepository
import com.bunch.place.domain.PinnedPlaceSource
import com.bunch.place.domain.PlaceDetails
import com.bunch.place.service.PinnedPlaceLocationListener
import com.bunch.place.service.PinnedPlaceService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class PinnedPlaceIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var pins: PinnedPlaceService

    @Autowired
    private lateinit var pinRows: PinnedPlaceRepository

    @Autowired
    private lateinit var locations: BundleLocationService

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var bundleService: BundleService

    @Autowired
    private lateinit var bunchRows: BunchRepository

    @Autowired
    private lateinit var social: BunchSocialService

    @Autowired
    private lateinit var hides: FeedContentHideRepository

    @Autowired
    private lateinit var listener: PinnedPlaceLocationListener

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @AfterEach
    fun cleanPlaces() {
        jdbc.execute("DROP TRIGGER IF EXISTS pin_test_reject_delete ON pinned_places")
        jdbc.execute("DROP FUNCTION IF EXISTS pin_test_reject_delete()")
        jdbc.update("DELETE FROM feed_content_hides")
        jdbc.update("DELETE FROM pinned_places")
        jdbc.update("DELETE FROM bundles")
    }

    @Test
    fun `duplicate place from another member and bundle preserves original snapshot provenance and time`() {
        val f = fixture()
        val colleague = member()
        join(colleague, issue(f.actor, f.bunch))
        val first = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        val secondBundle = bundle(f.sourceBunch, f.owner)
        val otherLocation = location(secondBundle, name = "changed name", address = "changed address")
        val duplicate = pins.pinBundleLocation(colleague, secondBundle, otherLocation)
        assertThat(duplicate).isEqualTo(first)
        assertThat(duplicate.sourceLocationId).isEqualTo(f.location)
        assertThat(duplicate.pinnedByMemberId).isEqualTo(f.actor)
        assertThat(duplicate.name).isEqualTo("original place")
        assertThat(count("pinned_places")).isEqualTo(1)
        val state = pins.forBundle(colleague, secondBundle)
        assertThat(state.currentBunchId).isEqualTo(f.bunch)
        assertThat(state.canPin).isTrue()
        assertThat(state.locations.single().pinId).isEqualTo(first.id)
        assertThat(state.locations.single().pinned).isTrue()
    }

    @Test
    fun `any active member can cancel someone else's pin and repeated cancel is harmless`() {
        val f = fixture()
        val colleague = member()
        join(colleague, issue(f.actor, f.bunch))
        val first = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        jdbc.update("DELETE FROM notifications")
        pins.unpin(colleague, first.id)
        pins.unpin(colleague, first.id)
        assertThat(count("pinned_places")).isZero()
        val repin = pins.pinBundleLocation(colleague, f.bundle, f.location)
        assertThat(repin.id).isNotEqualTo(first.id)
        assertThat(repin.pinnedByMemberId).isEqualTo(colleague)
        assertThat(count("notifications")).isZero()
    }

    @Test
    fun `server current bunch scopes uniqueness and never cancels another bunch pin`() {
        val f = fixture()
        val first = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        val secondBunch = create(f.actor).id
        val second = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        assertThat(second.bunchId).isEqualTo(secondBunch)
        assertThat(second.id).isNotEqualTo(first.id)
        pins.unpin(f.actor, first.id)
        assertThat(count("pinned_places")).isEqualTo(2)
        pins.unpin(f.actor, second.id)
        assertThat(pinRows.existsById(first.id)).isTrue()
        assertThat(pins.forBundle(f.actor, f.bundle).locations.single().pinned).isFalse()
    }

    @Test
    fun `provider separates otherwise identical external ids and batch matches exact keys`() {
        val f = fixture()
        val first = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        val differentProvider = location(f.bundle, provider = "google", position = 1)
        val second = pins.pinBundleLocation(f.actor, f.bundle, differentProvider)
        assertThat(second.id).isNotEqualTo(first.id)
        val state = pins.forBundle(f.actor, f.bundle)
        assertThat(state.locations.map { it.id }).containsExactly(f.location, differentProvider)
        assertThat(state.locations.map { it.pinId }).containsExactly(first.id, second.id)
        val actual = pins.findForPlaces(f.bunch, listOf(PlaceDetails.of("kakao", "external", "x", "y")))
        assertThat(actual.map { it.id }).containsExactly(first.id)
        assertThat(pins.findForPlaces(f.bunch, emptyList())).isEmpty()
        assertError(ErrorCode.INVALID_INPUT) {
            pins.findForPlaces(f.bunch, List(501) { PlaceDetails.of("kakao", "external", "x", "y") })
        }
    }

    @Test
    fun `simultaneous different members pinning same key from different bundles preserve one winner`() {
        val f = fixture()
        val colleague = member()
        join(colleague, issue(f.actor, f.bunch))
        val secondBundle = bundle(f.sourceBunch, f.owner)
        val secondLocation = location(secondBundle, name = "second")
        val results = concurrently(listOf(
            { pins.pinBundleLocation(f.actor, f.bundle, f.location) },
            { pins.pinBundleLocation(colleague, secondBundle, secondLocation) }
        )).map { it.getOrThrow() }
        assertThat(results[0]).isEqualTo(results[1])
        assertThat(count("pinned_places")).isEqualTo(1)
        assertThat(results[0].sourceLocationId).isIn(f.location, secondLocation)
    }

    @Test
    fun `own published private bundle can be pinned but drafts and mismatched locations cannot`() {
        val f = fixture()
        val own = bundle(f.bunch, f.actor, "PRIVATE")
        val ownLocation = location(own)
        assertThat(pins.pinBundleLocation(f.actor, own, ownLocation).sourceBundleId).isEqualTo(own)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { pins.pinBundleLocation(f.actor, f.bundle, ownLocation) }
        jdbc.update("UPDATE bundles SET status = 'DRAFT', published_at = NULL WHERE id = ?", own)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { pins.pinBundleLocation(f.actor, own, ownLocation) }
        assertError(ErrorCode.INVALID_INPUT) { pins.pinBundleLocation(f.actor, 0, ownLocation) }
        assertError(ErrorCode.INVALID_INPUT) { pins.pinBundleLocation(f.actor, f.bundle, -1) }
        assertError(ErrorCode.INVALID_INPUT) { pins.unpin(f.actor, 0) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { pins.pinBundleLocation(f.actor, Long.MAX_VALUE, ownLocation) }
    }

    @Test
    fun `zero memberships allow public reads but forbid writes and account guards apply`() {
        val f = fixture()
        val visitor = member()
        val state = pins.forBundle(visitor, f.bundle)
        assertThat(state.canPin).isFalse()
        assertThat(state.currentBunchId).isNull()
        assertThat(state.locations.single().pinId).isNull()
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { pins.pinBundleLocation(visitor, f.bundle, f.location) }
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { pins.unpin(visitor, 1) }
        val incomplete = member(false)
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { pins.forBundle(incomplete, f.bundle) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { pins.pinBundleLocation(incomplete, f.bundle, f.location) }
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", f.actor)
        assertError(ErrorCode.UNAUTHORIZED) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
    }

    @Test
    fun `private visibility follow cancellation and bilateral block are checked even for duplicate pins`() {
        val f = fixture()
        pins.pinBundleLocation(f.actor, f.bundle, f.location)
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", f.bundle)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
        jdbc.update("UPDATE bundles SET visibility = 'PUBLIC' WHERE id = ?", f.bundle)
        jdbc.update("UPDATE bunches SET visibility = 'PRIVATE' WHERE id = ?", f.sourceBunch)
        assertError(ErrorCode.FORBIDDEN) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
        val request = social.follow(f.actor, f.bunch, f.sourceBunch).followId!!
        assertError(ErrorCode.FORBIDDEN) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
        social.decide(f.owner, f.sourceBunch, request, FollowDecision.APPROVE)
        pins.pinBundleLocation(f.actor, f.bundle, f.location)
        social.cancel(f.actor, f.bunch, f.sourceBunch, request)
        assertError(ErrorCode.FORBIDDEN) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", f.sourceBunch)
        social.block(f.owner, f.sourceBunch, f.bunch)
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
        jdbc.update("DELETE FROM bunch_blocks")
        social.block(f.actor, f.bunch, f.sourceBunch)
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
    }

    @Test
    fun `profile reports reporter hide and global moderation deny source without preventing unpin`() {
        val f = fixture()
        val saved = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        social.report(f.actor, f.bunch, BunchReportRequest(f.sourceBunch, BunchReportReason.SPAM, null))
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
        jdbc.update("DELETE FROM bunch_profile_reports")
        tx { hides.hideForReporter(FeedContentKind.BUNDLE, f.bundle, f.bunch) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
        jdbc.update("DELETE FROM feed_content_hides")
        jdbc.update("INSERT INTO feed_content_hides(target_kind, target_id, reason) VALUES ('BUNDLE', ?, 'test')", f.bundle)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { pins.forBundle(f.actor, f.bundle) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
        pins.unpin(f.actor, saved.id)
        assertThat(count("pinned_places")).isZero()
    }

    @Test
    fun `actual location replacement removes only pins whose original source location was removed`() {
        val f = fixture()
        val secondActor = member()
        val secondBunch = create(secondActor).id
        val first = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        val alsoFromFirst = pins.pinBundleLocation(secondActor, f.bundle, f.location)
        val independent = bundle(f.sourceBunch, f.owner)
        val independentLocation = location(independent)
        val thirdActor = member()
        create(thirdActor)
        val elsewhere = pins.pinBundleLocation(thirdActor, independent, independentLocation)
        val advertisement = tx {
            pins.pinAdvertisement(f.actor, 123, PlaceDetails.of("google", "external", "ad", "address"))
        }
        tx { listener.removed(BundleLocationsRemovedEvent(independent, listOf(f.location))) }
        assertThat(pinRows.existsById(first.id)).isTrue()
        replace(f, emptyList())
        assertThat(pinRows.existsById(first.id)).isFalse()
        assertThat(pinRows.existsById(alsoFromFirst.id)).isFalse()
        assertThat(pinRows.existsById(elsewhere.id)).isTrue()
        assertThat(pinRows.existsById(advertisement.id)).isTrue()
        assertThat(pins.findForPlaces(secondBunch, listOf(place()))).isEmpty()
    }

    @Test
    fun `metadata and ordering changes retain source ids and original pin snapshot`() {
        val f = fixture()
        val another = location(f.bundle, externalId = "two", position = 1)
        val first = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        val second = pins.pinBundleLocation(f.actor, f.bundle, another)
        replace(f, listOf(input("two", 0), input("external", 1, "new name")))
        val actual = pins.forBundle(f.actor, f.bundle)
        assertThat(actual.locations.map { it.id }).containsExactly(another, f.location)
        assertThat(actual.locations.map { it.pinId }).containsExactly(second.id, first.id)
        assertThat(pinRows.findById(first.id).orElseThrow().name).isEqualTo("original place")
    }

    @Test
    fun `rollback after synchronous pin removal restores locations pins and aggregate data`() {
        val f = fixture()
        val saved = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        assertThatThrownBy {
            tx {
                val bundle = bundles.findLocked(f.bundle)!!
                locations.replaceLocked(bundle, emptyList())
                assertThat(count("pinned_places")).isZero()
                bundle.title = "should rollback"
                error("later field failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(pinRows.existsById(saved.id)).isTrue()
        assertThat(pins.forBundle(f.actor, f.bundle).locations.single().id).isEqualTo(f.location)
        assertThat(bundles.findById(f.bundle).orElseThrow().title).isEqualTo("published")
    }

    @Test
    fun `pin listener failure rolls back actual location editing and requires transaction`() {
        val f = fixture()
        pins.pinBundleLocation(f.actor, f.bundle, f.location)
        jdbc.execute("""CREATE FUNCTION pin_test_reject_delete() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''injected pin deletion failure''; END'""")
        jdbc.execute("""CREATE TRIGGER pin_test_reject_delete BEFORE DELETE ON pinned_places
            FOR EACH ROW EXECUTE FUNCTION pin_test_reject_delete()""")
        assertThatThrownBy { replace(f, emptyList()) }.isInstanceOf(RuntimeException::class.java)
        assertThat(count("pinned_places")).isEqualTo(1)
        assertThat(pins.forBundle(f.actor, f.bundle).locations.single().id).isEqualTo(f.location)
        assertThatThrownBy { listener.removed(BundleLocationsRemovedEvent(f.bundle, listOf(f.location))) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
    }

    @Test
    fun `bundle and source bunch cascade deletion preserve pins and null only source references`() {
        val f = fixture()
        val saved = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        bundleService.delete(BundleActor(f.owner, "test-session"), f.sourceBunch, f.bundle, UUID.randomUUID().toString())
        val after = pinRows.findById(saved.id).orElseThrow()
        assertThat(after.sourceBundleId).isNull()
        assertThat(after.sourceLocationId).isNull()
        assertThat(after.name).isEqualTo(saved.name)
        assertThat(after.address).isEqualTo(saved.address)
        assertThat(after.pinnedAt).isEqualTo(saved.pinnedAt)
        val secondBundle = bundle(f.sourceBunch, f.owner)
        val secondLocation = location(secondBundle, externalId = "second")
        val secondPin = pins.pinBundleLocation(f.actor, secondBundle, secondLocation)
        jdbc.update("DELETE FROM bunches WHERE id = ?", f.sourceBunch)
        assertThat(pinRows.findById(secondPin.id).orElseThrow().sourceLocationId).isNull()
        assertThat(count("pinned_places")).isEqualTo(2)
        jdbc.update("DELETE FROM bunches WHERE id = ?", f.bunch)
        assertThat(count("pinned_places")).isZero()
    }

    @Test
    fun `advertisement internal contract requires transaction and preserves either original source`() {
        val f = fixture()
        assertThatThrownBy { pins.pinAdvertisement(f.actor, 123, place()) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
        val bundleFirst = pins.pinBundleLocation(f.actor, f.bundle, f.location)
        assertThat(tx { pins.pinAdvertisement(f.actor, 123, place()) }).isEqualTo(bundleFirst)
        pins.unpin(f.actor, bundleFirst.id)
        val adFirst = tx { pins.pinAdvertisement(f.actor, 123, place()) }
        assertThat(adFirst.sourceType).isEqualTo(PinnedPlaceSource.ADVERTISEMENT)
        assertThat(adFirst.sourceAdvertisementId).isEqualTo(123)
        assertThat(pins.pinBundleLocation(f.actor, f.bundle, f.location)).isEqualTo(adFirst)
        replace(f, emptyList())
        assertThat(pinRows.existsById(adFirst.id)).isTrue()
        assertError(ErrorCode.INVALID_INPUT) { tx { pins.pinAdvertisement(f.actor, 0, place()) } }
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { tx { pins.pinAdvertisement(member(), 123, place()) } }
    }

    @Test
    fun `waiting pin rechecks latest source visibility under bundle write lock`() {
        val f = fixture()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val editor = executor.submit {
                tx {
                    bunchRows.findLocked(f.sourceBunch)
                    val bundle = bundles.findLocked(f.bundle)!!
                    bundle.visibility = com.bunch.bunch.domain.Visibility.PRIVATE
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            check(locked.await(10, TimeUnit.SECONDS))
            val pin = executor.submit<Any> {
                assertError(ErrorCode.RESOURCE_NOT_FOUND) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
            }
            awaitDatabaseLock()
            release.countDown()
            editor.get(15, TimeUnit.SECONDS)
            pin.get(15, TimeUnit.SECONDS)
            assertThat(count("pinned_places")).isZero()
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `membership removed while waiting for bunch lock cannot pin with preflight membership`() {
        val f = fixture()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val remover = executor.submit {
                tx {
                    bunchRows.findLocked(f.bunch)
                    jdbc.update("UPDATE bunch_memberships SET status = 'REMOVED' WHERE member_id = ?", f.actor)
                    locked.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            check(locked.await(10, TimeUnit.SECONDS))
            val pin = executor.submit<Any> {
                assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { pins.pinBundleLocation(f.actor, f.bundle, f.location) }
            }
            awaitDatabaseLock()
            release.countDown()
            remover.get(15, TimeUnit.SECONDS)
            pin.get(15, TimeUnit.SECONDS)
            assertThat(count("pinned_places")).isZero()
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `simultaneous location removal and pin cannot leave a stale snapshot from removed source`() {
        val f = fixture()
        val results = concurrently(listOf(
            { runCatching { pins.pinBundleLocation(f.actor, f.bundle, f.location) } },
            { runCatching { replace(f, emptyList()) } }
        ))
        results.forEach { it.getOrThrow() }
        val pinResult = results[0].getOrThrow()
        if (pinResult.isFailure) {
            val error = pinResult.exceptionOrNull() as com.bunch.global.exception.BusinessException
            assertThat(error.errorCode).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND)
        }
        results[1].getOrThrow().getOrThrow()
        assertThat(count("pinned_places")).isZero()
        assertThat(pins.forBundle(f.actor, f.bundle).locations).isEmpty()
    }

    private fun awaitDatabaseLock() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiting = jdbc.queryForObject(
                """SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'""", Long::class.java
            )!!
            if (waiting > 0) return
            Thread.sleep(10)
        }
        error("pin request did not reach its database lock")
    }

    private fun fixture(): Fixture {
        val actor = member()
        val bunch = create(actor).id
        val owner = member()
        val sourceBunch = create(owner).id
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", sourceBunch)
        val bundle = bundle(sourceBunch, owner)
        return Fixture(actor, bunch, owner, sourceBunch, bundle, location(bundle))
    }

    private fun bundle(bunch: Long, owner: Long, visibility: String = "PUBLIC"): Long = jdbc.queryForObject(
        """INSERT INTO bundles(bunch_id, created_by, status, title, visibility, created_at, updated_at, published_at)
            VALUES (?, ?, 'PUBLISHED', 'published', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id""", Long::class.java, bunch, owner, visibility
    )!!

    private fun location(
        bundle: Long,
        provider: String = "kakao",
        externalId: String = "external",
        name: String = "original place",
        address: String = "original address",
        position: Int = 0
    ): Long = jdbc.queryForObject(
        """INSERT INTO bundle_locations(bundle_id, provider, external_id, name, address, position)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long::class.java,
        bundle, provider, externalId, name, address, position
    )!!

    private fun replace(f: Fixture, inputs: List<BundleLocationInput>) = tx {
        bunchRows.findLocked(f.sourceBunch)
        locations.replaceLocked(bundles.findLocked(f.bundle)!!, inputs)
    }

    private fun input(id: String, position: Int, name: String = "original place") =
        BundleLocationInput("kakao", id, name, "original address", position)

    private fun place() = PlaceDetails.of("kakao", "external", "original place", "original address")

    private fun <T> tx(action: () -> T): T = TransactionTemplate(transactions).execute { action() }!!

    private data class Fixture(
        val actor: Long,
        val bunch: Long,
        val owner: Long,
        val sourceBunch: Long,
        val bundle: Long,
        val location: Long
    )
}
