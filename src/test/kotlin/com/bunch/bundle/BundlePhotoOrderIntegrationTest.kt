package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundlePhoto
import com.bunch.bundle.domain.BundlePhotoOrderMarkRepository
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.MoveBundlePhotoRequest
import com.bunch.bundle.service.BundleAccessService
import com.bunch.bundle.service.BundlePhotoOrderService
import com.bunch.bundle.service.BundleService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BundlePhotoOrderIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var service: BundleService

    @Autowired
    private lateinit var order: BundlePhotoOrderService

    @Autowired
    private lateinit var access: BundleAccessService

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var participants: BundleParticipantRepository

    @Autowired
    private lateinit var photos: BundlePhotoRepository

    @Autowired
    private lateinit var marks: BundlePhotoOrderMarkRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    private val assetIds = mutableListOf<Long>()

    @AfterEach
    fun clearGraph() {
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM bundle_requests")
        assetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
    }

    @Test
    fun `nonadjacent anchors retain intervening photos and same gap follows server processing order`() {
        val f = fixture(6)
        val (a, b, c, d, e) = f.ids
        val x = f.ids[5]
        move(f, e, a, d, "gap-first")
        move(f, x, a, d, "gap-second")
        assertOrder(f, a, b, c, e, x, d)
        assertThat(state(f).revision).isEqualTo(2)
    }

    @Test
    fun `previous only gap appends distinct moves rather than reversing their arrival order`() {
        val f = fixture(5)
        val (a, b, c, d, e) = f.ids
        move(f, d, a, null, "left-first")
        move(f, e, a, null, "left-second")
        assertOrder(f, a, d, e, b, c)
    }

    @Test
    fun `next only gap retains arrival order at the beginning`() {
        val f = fixture(4)
        val (a, b, c, d) = f.ids
        move(f, c, null, a, "right-first")
        move(f, d, null, a, "right-second")
        assertOrder(f, c, d, a, b)
    }

    @Test
    fun `same gap uses latest right boundary when next anchor moves after first peer`() {
        val f = fixture(6)
        val (a, b, c, d, e) = f.ids
        val x = f.ids[5]
        move(f, e, a, b, "peer-one")
        move(f, b, d, null, "move-right-anchor")
        move(f, x, a, b, "peer-two")
        assertOrder(f, a, e, c, d, x, b)
    }

    @Test
    fun `same gap ignores old peer metadata after that peer moves elsewhere`() {
        val f = fixture(5)
        val (a, b, c, d, e) = f.ids
        move(f, d, a, null, "peer-old-gap")
        move(f, d, c, null, "peer-new-gap")
        move(f, e, a, null, "new-peer-old-gap")
        assertOrder(f, a, e, b, c, d)
    }

    @Test
    fun `same photo last new move wins and old request replay cannot undo it`() {
        val f = fixture(4)
        val (a, b, c, d) = f.ids
        move(f, d, a, b, "legacy-string-request")
        val last = move(f, d, c, null, "latest-string-request")
        val replay = move(f, d, a, b, "legacy-string-request")
        assertOrder(f, a, b, c, d)
        assertThat(replay.revision).isEqualTo(last.revision)
        assertThat(replay.updatedAt).isEqualTo(last.updatedAt)
        assertThat(requestCount(f)).isEqualTo(2)
    }

    @Test
    fun `reusing a key with changed anchors or another bundle fails fingerprint validation`() {
        val f = fixture(4)
        val (a, b, c, d) = f.ids
        move(f, d, a, b, "same-key")
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) { move(f, d, c, null, "same-key") }
        val other = addBundle(f.memberId, f.bunchId, 3)
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) {
            move(other, other.ids.last(), null, other.ids.first(), "same-key")
        }
        assertOrder(f, a, d, b, c)
        assertOrder(other, *other.ids.toLongArray())
    }

    @Test
    fun `unchanged move is remembered and cannot undo a later real move`() {
        val f = fixture(3)
        val (a, b, c) = f.ids
        val unchanged = move(f, b, a, c, "initial-noop")
        assertThat(unchanged.revision).isZero()
        move(f, b, c, null, "later-change")
        val replay = move(f, b, a, c, "initial-noop")
        assertOrder(f, a, c, b)
        assertThat(replay.revision).isEqualTo(1)
        assertThat(requestCount(f)).isEqualTo(2)
    }

    @Test
    fun `deleted previous anchor keeps former slot and remains durable across transactions`() {
        val f = fixture(4)
        val (a, b, c, d) = f.ids
        remove(f, b)
        val tombstone = marks.findById(b).orElseThrow()
        assertThat(tombstone.deleted).isTrue()
        assertThat(tombstone.previousId).isEqualTo(a)
        assertThat(tombstone.nextId).isEqualTo(c)
        assertThat(tombstone.slot).isEqualTo(1)
        move(f, d, b, null, "after-deleted-anchor")
        assertOrder(f, a, d, c)
    }

    @Test
    fun `deleted next anchor preserves same gap arrival order`() {
        val f = fixture(5)
        val (a, b, c, d, e) = f.ids
        remove(f, b)
        move(f, d, a, b, "deleted-right-first")
        move(f, e, a, b, "deleted-right-second")
        assertOrder(f, a, d, e, c)
    }

    @Test
    fun `batch deleted anchors follow neighbor chain to surviving boundary`() {
        val f = fixture(6)
        val (a, b, c, d, e) = f.ids
        val x = f.ids[5]
        remove(f, b, c)
        move(f, x, b, c, "batch-deleted")
        assertOrder(f, a, x, d, e)
        assertThat(marks.findById(b).orElseThrow().nextId).isEqualTo(c)
    }

    @Test
    fun `separate deletions resolve old neighbor chain and missing neighbors fall back to slot`() {
        val f = fixture(5)
        val (a, b, c, d, e) = f.ids
        remove(f, b)
        remove(f, a, c)
        move(f, e, b, null, "deleted-chain")
        assertOrder(f, e, d)

        val g = fixture(3)
        val (left, anchor, target) = g.ids
        remove(g, left, anchor)
        // The only former live neighbor is the moving target itself: clamp the old slot.
        move(g, target, anchor, null, "slot-fallback")
        assertOrder(g, target)
    }

    @Test
    fun `deleted target and delayed duplicate do not resurrect a physical photo row`() {
        val f = fixture(4)
        val (a, b, c, d) = f.ids
        move(f, d, a, b, "move-before-delete")
        remove(f, d)
        val before = state(f)
        move(f, d, a, b, "move-before-delete")
        move(f, d, c, null, "new-move-after-delete")
        assertOrder(f, a, b, c)
        assertThat(photos.existsById(d)).isFalse()
        assertThat(state(f).revision).isEqualTo(before.revision)
        assertThat(requestCount(f)).isEqualTo(2)
    }

    @Test
    fun `unknown and foreign anchors remain conflicts and unknown target remains not found`() {
        val f = fixture(3)
        val foreign = fixture(2)
        val (a, b, c) = f.ids
        assertError(ErrorCode.BUNDLE_PHOTO_CONFLICT) { move(f, c, a, Long.MAX_VALUE, "unknown") }
        assertError(ErrorCode.BUNDLE_PHOTO_CONFLICT) { move(f, c, null, foreign.ids.first(), "foreign-live") }
        remove(foreign, foreign.ids.first())
        assertError(ErrorCode.BUNDLE_PHOTO_CONFLICT) { move(f, c, foreign.ids.first(), b, "foreign-dead") }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { move(f, Long.MAX_VALUE, a, b, "unknown-target") }
        assertError(ErrorCode.BUNDLE_PHOTO_CONFLICT) { move(f, c, c, b, "self") }
        assertError(ErrorCode.BUNDLE_PHOTO_CONFLICT) { move(f, c, a, a, "duplicate-anchors") }
        assertError(ErrorCode.BUNDLE_PHOTO_CONFLICT) { move(f, c, null, null, "no-anchors") }
        assertThat(requestCount(f)).isZero()
        assertOrder(f, a, b, c)
    }

    @Test
    fun `rollback discards positions metadata and request receipt so retry remains possible`() {
        val f = fixture(4)
        val (a, b, c, d) = f.ids
        assertThatThrownBy {
            transaction {
                val bundle = access.lock(f.memberId, f.bunchId, f.bundleId)
                assertThat(order.moveLocked(bundle, f.memberId, d, a, b, "rollback-key")).isTrue()
                order.beforeRemoveLocked(bundle, listOf(photos.findById(c).orElseThrow()))
                photos.deleteById(c)
                photos.flush()
                error("rollback after physical delete")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertOrder(f, a, b, c, d)
        assertThat(marks.findAllByBundleId(f.bundleId)).isEmpty()
        assertThat(requestCount(f)).isZero()
        move(f, d, a, b, "rollback-key")
        assertOrder(f, a, d, b, c)
    }

    @Test
    fun `parallel distinct moves serialize without duplicates or losses`() {
        val f = fixture(6)
        val (a, b, c, d, e) = f.ids
        val x = f.ids[5]
        val targets = listOf(d, e, x)
        val responses = concurrently(targets.map { target ->
            { target to move(f, target, a, b, "parallel-$target").revision }
        }).map { it.getOrThrow() }
        val processingOrder = responses.sortedBy { it.second }.map { it.first }
        assertOrder(f, *(listOf(a) + processingOrder + listOf(b, c)).toLongArray())
        assertThat(requestCount(f)).isEqualTo(3)
        assertThat(state(f).revision).isEqualTo(3)
    }

    @Test
    fun `move before delete and delete before move both end with deletion winning`() {
        val f = fixture(4)
        val (a, b, _, d) = f.ids
        val outcomes = concurrently(listOf(
            { move(f, d, a, b, "race-move"); Unit },
            { remove(f, d) }
        ))
        outcomes.forEach { it.getOrThrow() }
        assertThat(photos.existsById(d)).isFalse()
        assertThat(state(f).photos.map { it.id }).containsExactlyElementsOf(f.ids.filterNot { it == d })
        assertThat(marks.findById(d).orElseThrow().deleted).isTrue()
    }

    @Test
    fun `metadata cascades only on aggregate deletion`() {
        val f = fixture(3)
        val (a, b, c) = f.ids
        move(f, c, null, a, "cascade-move")
        remove(f, b)
        assertThat(marks.findAllByBundleId(f.bundleId)).hasSize(2)
        transaction {
            val bundle = access.lock(f.memberId, f.bunchId, f.bundleId)
            bundles.delete(bundle)
            bundles.flush()
        }
        assertThat(marks.findAllByBundleId(f.bundleId)).isEmpty()
        assertThat(requestCount(f)).isEqualTo(1)
    }

    private fun fixture(size: Int): Fixture {
        val owner = member()
        val guest = member()
        val bunchId = create(owner).id
        join(guest, issue(owner, bunchId))
        return addBundle(owner, bunchId, size)
    }

    private fun addBundle(memberId: Long, bunchId: Long, size: Int): Fixture = transaction {
        val bundle = bundles.saveAndFlush(Bundle(bunchId = bunchId, createdBy = memberId))
        participants.saveAndFlush(BundleParticipant(bundleId = bundle.id, memberId = memberId))
        val asset = assets.saveAndFlush(ImageAsset(
            publicId = "photo-order-test/${UUID.randomUUID()}",
            url = "https://images.example/photo-order-test.jpg"
        ))
        assetIds += asset.id
        val ids = (0 until size).map { position ->
            photos.saveAndFlush(BundlePhoto(
                bundleId = bundle.id,
                photoId = null,
                imageAsset = asset,
                position = position,
                addedBy = memberId
            )).id
        }
        Fixture(memberId, bunchId, bundle.id, ids)
    }

    private fun move(f: Fixture, target: Long, previous: Long?, next: Long?, key: String) = service.move(
        BundleActor(f.memberId, "photo-order-test"),
        f.bunchId,
        f.bundleId,
        target,
        MoveBundlePhotoRequest(previous, next, BundleStatus.DRAFT),
        key
    )

    private fun remove(f: Fixture, vararg ids: Long) {
        transaction {
            val bundle = access.lock(f.memberId, f.bunchId, f.bundleId)
            val rows = photos.findAllByBundleIdOrderByPositionAscIdAsc(bundle.id).filter { it.id in ids }
            order.beforeRemoveLocked(bundle, rows)
            photos.deleteAll(rows)
            photos.flush()
            photos.findAllByBundleIdOrderByPositionAscIdAsc(bundle.id).forEachIndexed { index, row ->
                row.position = index
            }
        }
    }

    private fun state(f: Fixture) = service.get(f.memberId, f.bunchId, f.bundleId)

    private fun assertOrder(f: Fixture, vararg ids: Long) {
        val current = state(f).photos
        assertThat(current.map { it.id }).containsExactlyElementsOf(ids.toList())
        assertThat(current.map { it.id }.distinct()).hasSize(current.size)
        assertThat(current.map { it.position }).containsExactlyElementsOf(current.indices.toList())
    }

    private fun requestCount(f: Fixture): Long = jdbc.queryForObject(
        "SELECT count(*) FROM bundle_requests WHERE bundle_id = ? AND operation = 'PHOTO_MOVE'",
        Long::class.java,
        f.bundleId
    )!!

    private fun <T : Any> transaction(action: () -> T): T =
        TransactionTemplate(transactionManager).execute { action() }!!

    private data class Fixture(
        val memberId: Long,
        val bunchId: Long,
        val bundleId: Long,
        val ids: List<Long>
    )
}
