package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.*
import com.bunch.bundle.service.BundleService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.ProcessingStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.LocalDate
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BundleFinalPublishIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var service: BundleService
    @Autowired
    private lateinit var bunchRepository: BunchRepository
    @Autowired
    private lateinit var bundles: BundleRepository
    @Autowired
    private lateinit var photos: PhotoRepository
    @Autowired
    private lateinit var assets: ImageAssetRepository
    @MockitoBean
    private lateinit var storage: ProfileImageStorage
    private val assetIds = mutableListOf<Long>()

    @AfterEach
    fun clearGraph() {
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photos")
        assetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
    }

    @Test
    fun `first upload needs no selected visibility and final upload publishes latest content atomically`() {
        val f = fixture()
        var state = service.get(f.creator, f.bunchId, f.bundleId)
        assertThat(state.visibility).isNull()
        assertThat(state.publishBlockers).isEmpty()
        val staleRevision = state.revision
        state = service.title(actor(f.creator), f.bunchId, f.bundleId,
            BundleTextRequest("최종 저장 제목", state.titleVersion, BundleStatus.DRAFT), "latest-title")
        val key = UUID.randomUUID()
        val published = service.publish(actor(f.publisher), f.bunchId, f.bundleId, key,
            PublishBundleRequest(staleRevision, Visibility.PUBLIC))
        assertThat(published.title).isEqualTo("최종 저장 제목")
        assertThat(published.visibility).isEqualTo(Visibility.PUBLIC)
        assertThat(published.status).isEqualTo(BundleStatus.PUBLISHED)
        assertThat(published.publishedBy).isEqualTo(f.publisher)
        assertThat(published.publishedBy).isNotEqualTo(published.createdBy)
        assertThat(published.revision).isEqualTo(state.revision + 1)
        val retry = service.publish(actor(f.publisher), f.bunchId, f.bundleId, key,
            PublishBundleRequest(staleRevision, Visibility.PUBLIC))
        assertThat(retry.revision).isEqualTo(published.revision)
        assertThat(service.get(f.creator, f.bunchId, f.bundleId).publishedBy).isEqualTo(f.publisher)
        assertThatThrownBy {
            service.date(actor(f.creator), f.bunchId, f.bundleId,
                BundleDateRequest(LocalDate.of(2024, 1, 1), 0, BundleStatus.DRAFT), "late")
        }.isInstanceOfSatisfying(BusinessException::class.java) {
            assertThat(it.errorCode).isEqualTo(ErrorCode.BUNDLE_STATE_CONFLICT)
        }
    }

    @Test
    fun `failed final upload rolls back selection and never records a publisher`() {
        val f = fixture()
        val state = service.get(f.creator, f.bunchId, f.bundleId)
        service.comment(actor(f.creator), f.bunchId, f.bundleId,
            BundleTextRequest("", state.comments.first { it.memberId == f.creator }.version, BundleStatus.DRAFT), "clear")
        assertThatThrownBy {
            service.publish(actor(f.publisher), f.bunchId, f.bundleId, UUID.randomUUID(),
                PublishBundleRequest(state.revision, Visibility.PUBLIC))
        }.isInstanceOfSatisfying(BusinessException::class.java) {
            assertThat(it.errorCode).isEqualTo(ErrorCode.BUNDLE_PUBLISH_BLOCKED)
        }
        val after = service.get(f.creator, f.bunchId, f.bundleId)
        assertThat(after.visibility).isNull()
        assertThat(after.publishedBy).isNull()
        assertThat(after.status).isEqualTo(BundleStatus.DRAFT)
    }

    @Test
    fun `dates use last stored value without field version or lease and still validate future dates`() {
        val f = fixture()
        val first = service.date(actor(f.creator), f.bunchId, f.bundleId,
            BundleDateRequest(LocalDate.of(2024, 1, 1), 0, BundleStatus.DRAFT), "one")
        val second = service.date(actor(f.publisher), f.bunchId, f.bundleId,
            BundleDateRequest(LocalDate.of(2024, 2, 1), 0, BundleStatus.DRAFT), "two")
        assertThat(second.date).isEqualTo(LocalDate.of(2024, 2, 1))
        assertThat(second.dateVersion).isEqualTo(first.dateVersion + 1)
        assertThatThrownBy {
            service.date(actor(f.creator), f.bunchId, f.bundleId,
                BundleDateRequest(LocalDate.of(2999, 1, 1), 0, BundleStatus.DRAFT), "future")
        }.isInstanceOfSatisfying(BusinessException::class.java) {
            assertThat(it.errorCode).isEqualTo(ErrorCode.INVALID_INPUT)
        }
    }

    @Test
    fun `concurrent final uploads with different scopes publish only one immutable selection`() {
        val f = fixture()
        val before = service.get(f.creator, f.bunchId, f.bundleId)
        val results = concurrently(listOf(Visibility.PUBLIC, Visibility.PRIVATE).map { scope ->
            {
                service.publish(actor(f.publisher), f.bunchId, f.bundleId, UUID.randomUUID(),
                    PublishBundleRequest(0, scope))
            }
        })
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        val winner = results.single { it.isSuccess }.getOrThrow()
        val stored = service.get(f.creator, f.bunchId, f.bundleId)
        assertThat(stored.visibility).isEqualTo(winner.visibility)
        assertThat(stored.revision).isEqualTo(before.revision + 1)
        assertThat(stored.publishedBy).isEqualTo(f.publisher)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bundle_requests WHERE bundle_id = ? AND operation = ?",
            Long::class.java, f.bundleId, "PUBLISH:${f.bundleId}"
        )).isEqualTo(1)
    }

    @Test
    fun `revoked administrator cannot publish and the submitted scope remains uncommitted`() {
        val f = fixture()
        jdbc.update("UPDATE bunch_memberships SET role = 'MEMBER' WHERE bunch_id = ? AND member_id = ?",
            f.bunchId, f.publisher)
        assertThatThrownBy {
            service.publish(actor(f.publisher), f.bunchId, f.bundleId, UUID.randomUUID(),
                PublishBundleRequest(0, Visibility.PUBLIC))
        }.isInstanceOfSatisfying(BusinessException::class.java) {
            assertThat(it.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
        }
        val stored = service.get(f.creator, f.bunchId, f.bundleId)
        assertThat(stored.visibility).isNull()
        assertThat(stored.publishedBy).isNull()
    }

    @Test
    fun `concurrent CRDT overflow is preserved persisted and blocks final publish until reduced`() {
        val f = fixture()
        val state = service.get(f.creator, f.bunchId, f.bundleId)
        val rebased = service.title(actor(f.creator), f.bunchId, f.bundleId,
            BundleTextRequest("가".repeat(39), state.titleVersion, BundleStatus.DRAFT), "base")
        val base = requireNotNull(rebased.titleCrdtState)
        val one = editTitle(base, 0, "A")
        val two = editTitle(base, 39, "B")
        service.mergeTitle(actor(f.creator), f.bunchId, f.bundleId, one, "one")
        val merged = service.mergeTitle(actor(f.publisher), f.bunchId, f.bundleId, two, "two")
        assertThat(merged.title).hasSize(41)
        assertThat(merged.titleTooLong).isTrue()
        val saved = service.get(f.creator, f.bunchId, f.bundleId)
        assertThat(saved.title).isEqualTo(merged.title)
        assertThat(saved.titleCrdtState).containsExactly(*merged.titleCrdtState)
        assertThat(saved.publishBlockers).contains("TITLE_TOO_LONG")
        val repeated = service.mergeTitle(actor(f.publisher), f.bunchId, f.bundleId, two, "repeat")
        assertThat(repeated.titleVersion).isEqualTo(merged.titleVersion)
        assertThat(service.get(f.creator, f.bunchId, f.bundleId).revision).isEqualTo(saved.revision)
        assertThatThrownBy {
            service.publish(actor(f.publisher), f.bunchId, f.bundleId, UUID.randomUUID(),
                PublishBundleRequest(0, Visibility.PUBLIC))
        }.isInstanceOfSatisfying(BusinessException::class.java) {
            assertThat(it.errorCode).isEqualTo(ErrorCode.BUNDLE_PUBLISH_BLOCKED)
        }
        val reduced = service.title(actor(f.creator), f.bunchId, f.bundleId,
            BundleTextRequest("짧은 제목", saved.titleVersion, BundleStatus.DRAFT), "reduce")
        assertThat(reduced.titleTooLong).isFalse()
        service.publish(actor(f.publisher), f.bunchId, f.bundleId, UUID.randomUUID(),
            PublishBundleRequest(0, Visibility.PUBLIC))
        assertThatThrownBy { service.mergeTitle(actor(f.creator), f.bunchId, f.bundleId, two, "late") }
            .isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun `invalid title state and removed participant never mutate saved title`() {
        val f = fixture()
        val before = service.get(f.creator, f.bunchId, f.bundleId)
        assertThatThrownBy {
            service.mergeTitle(actor(f.creator), f.bunchId, f.bundleId, byteArrayOf(0), "invalid")
        }.isInstanceOf(BusinessException::class.java)
        assertThat(service.get(f.creator, f.bunchId, f.bundleId).revision).isEqualTo(before.revision)
        service.removeParticipant(actor(f.creator), f.bunchId, f.bundleId, f.publisher, "remove")
        assertThatThrownBy {
            service.mergeTitle(actor(f.publisher), f.bunchId, f.bundleId,
                editTitle(requireNotNull(before.titleCrdtState), 0, "삭제 후"), "late")
        }.isInstanceOfSatisfying(BusinessException::class.java) {
            assertThat(it.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
        }
    }

    @Test
    fun `gallery additions accept remaining capacity in selection order while library apply rolls back`() {
        val f = fixture()
        val sources = (1..21).map { number ->
            val asset = assets.saveAndFlush(ImageAsset(
                publicId = "capacity107/${UUID.randomUUID()}", url = "https://images.example/$number.jpg"
            ))
            assetIds += asset.id
            photos.saveAndFlush(Photo(
                bunch = bunchRepository.findById(f.bunchId).orElseThrow(), imageAsset = asset,
                fileHash = "%064x".format(number), processingStatus = ProcessingStatus.READY
            )).id
        }
        service.changePhotos(actor(f.creator), f.bunchId, f.bundleId, UUID.randomUUID(),
            BundlePhotoChangesRequest(sources.take(18), mode = BundleStatus.DRAFT))
        val before = service.get(f.creator, f.bunchId, f.bundleId)
        assertThat(before.photos).hasSize(19)
        assertThatThrownBy {
            service.changePhotos(actor(f.creator), f.bunchId, f.bundleId, UUID.randomUUID(),
                BundlePhotoChangesRequest(sources.takeLast(3), mode = BundleStatus.DRAFT))
        }.isInstanceOf(BusinessException::class.java)
        assertThat(service.get(f.creator, f.bunchId, f.bundleId).revision).isEqualTo(before.revision)
        val key = UUID.randomUUID()
        val request = BundlePhotoChangesRequest(
            sources.takeLast(3), mode = BundleStatus.DRAFT,
            capacityMode = com.bunch.bundle.domain.BundlePhotoCapacityMode.PARTIAL_ADD
        )
        val partial = service.changePhotos(actor(f.creator), f.bunchId, f.bundleId, key, request)
        assertThat(partial.photos).hasSize(20)
        assertThat(partial.photos.last().photoId).isEqualTo(sources[18])
        assertThat(partial.photoChangeResult!!.addedPhotoIds).containsExactly(sources[18])
        assertThat(partial.photoChangeResult!!.rejectedPhotoIds).containsExactly(sources[19], sources[20])
        val repeated = service.changePhotos(actor(f.creator), f.bunchId, f.bundleId, key, request)
        assertThat(repeated.revision).isEqualTo(partial.revision)
        assertThat(repeated.photoChangeResult!!.replayed).isTrue()
    }

    @Test
    fun `two gallery requests racing for the last slot never exceed twenty photos`() {
        val f = fixture()
        val sourceIds = (1..20).map { number ->
            val asset = assets.saveAndFlush(ImageAsset(
                publicId = "race107/${UUID.randomUUID()}", url = "https://images.example/race$number.jpg"
            ))
            assetIds += asset.id
            photos.saveAndFlush(Photo(
                bunch = bunchRepository.findById(f.bunchId).orElseThrow(), imageAsset = asset,
                fileHash = "%064x".format(number), processingStatus = ProcessingStatus.READY
            )).id
        }
        service.changePhotos(actor(f.creator), f.bunchId, f.bundleId, UUID.randomUUID(),
            BundlePhotoChangesRequest(sourceIds.take(18), mode = BundleStatus.DRAFT))
        val results = concurrently(listOf(f.creator, f.publisher).mapIndexed { index, memberId ->
            {
                service.changePhotos(actor(memberId), f.bunchId, f.bundleId, UUID.randomUUID(),
                    BundlePhotoChangesRequest(
                        listOf(sourceIds[18 + index]), mode = BundleStatus.DRAFT,
                        capacityMode = com.bunch.bundle.domain.BundlePhotoCapacityMode.PARTIAL_ADD
                    ))
            }
        }).map { it.getOrThrow() }
        assertThat(results.sumOf { it.photoChangeResult!!.addedPhotoIds.size }).isEqualTo(1)
        assertThat(results.sumOf { it.photoChangeResult!!.rejectedPhotoIds.size }).isEqualTo(1)
        val stored = service.get(f.creator, f.bunchId, f.bundleId)
        assertThat(stored.photos).hasSize(20)
        assertThat(stored.photos.map { it.id }.distinct()).hasSize(20)
    }

    @Test
    fun `legacy photo and publish receipts retain pre transition fingerprints`() {
        val f = fixture()
        val photoKey = UUID.randomUUID()
        service.changePhotos(actor(f.creator), f.bunchId, f.bundleId, photoKey,
            BundlePhotoChangesRequest(mode = BundleStatus.DRAFT))
        fun hash(json: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
        fun stored(operation: String, key: UUID): String = jdbc.queryForObject(
            "SELECT fingerprint FROM bundle_requests WHERE bundle_id = ? AND operation = ? AND request_key = ?",
            String::class.java, f.bundleId, operation, key
        )!!
        assertThat(stored("PHOTOS:${f.bundleId}", photoKey)).isEqualTo(hash(
            """{"addPhotoIds":[],"removeBundlePhotoIds":[],"mode":"DRAFT"}"""
        ))
        val partialKey = UUID.randomUUID()
        service.changePhotos(actor(f.creator), f.bunchId, f.bundleId, partialKey,
            BundlePhotoChangesRequest(
                mode = BundleStatus.DRAFT,
                capacityMode = com.bunch.bundle.domain.BundlePhotoCapacityMode.PARTIAL_ADD
            ))
        assertThat(stored("PHOTOS:${f.bundleId}", partialKey)).isEqualTo(hash(
            """{"addPhotoIds":[],"removeBundlePhotoIds":[],"mode":"DRAFT","capacityMode":"PARTIAL_ADD"}"""
        ))
        service.visibility(actor(f.creator), f.bunchId, f.bundleId,
            BundleVisibilityRequest(Visibility.PRIVATE, 0, BundleStatus.DRAFT), "scope")
        val publishKey = UUID.randomUUID()
        service.publish(actor(f.publisher), f.bunchId, f.bundleId, publishKey, PublishBundleRequest(0))
        assertThat(stored("PUBLISH:${f.bundleId}", publishKey)).isEqualTo(hash("""{"expectedRevision":0}"""))
        val retry = service.publish(actor(f.publisher), f.bunchId, f.bundleId,
            UUID.randomUUID(), PublishBundleRequest(0))
        assertThat(retry.status).isEqualTo(BundleStatus.PUBLISHED)
    }

    private fun editTitle(base: ByteArray, index: Long, value: String): ByteArray {
        val document = org.automerge.Document.load(base)
        try {
            val text = (document.get(org.automerge.ObjectId.ROOT, "title").get() as org.automerge.AmValue.Text).id
            document.startTransaction().use {
                it.spliceText(text, index, 0, value)
                it.commit()
            }
            return document.save()
        } finally {
            document.free()
        }
    }

    private fun fixture(): Fixture {
        val publisher = member()
        val creator = member()
        val bunchId = create(publisher).id
        join(creator, issue(publisher, bunchId))
        val asset = assets.saveAndFlush(ImageAsset(publicId = "publish107/${UUID.randomUUID()}",
            url = "https://images.example/107.jpg"))
        assetIds += asset.id
        val photo = photos.saveAndFlush(Photo(bunch = bunchRepository.findById(bunchId).orElseThrow(),
            imageAsset = asset, fileHash = "a".repeat(64), processingStatus = ProcessingStatus.READY))
        val draft = service.create(actor(creator), bunchId, UUID.randomUUID(), CreateBundleRequest(listOf(photo.id), participantMemberIds = listOf(publisher)))
        service.addParticipant(actor(creator), bunchId, draft.id, publisher, "add")
        service.title(actor(creator), bunchId, draft.id,
            BundleTextRequest("제목", 0, BundleStatus.DRAFT), "title")
        val current = service.get(creator, bunchId, draft.id)
        listOf(creator, publisher).forEach { id ->
            service.comment(actor(id), bunchId, draft.id,
                BundleTextRequest("코멘트", current.comments.firstOrNull { it.memberId == id }?.version ?: 0,
                    BundleStatus.DRAFT), "comment-$id")
        }
        return Fixture(creator, publisher, bunchId, draft.id)
    }

    private fun actor(memberId: Long) = BundleActor(memberId, "publish107-$memberId")
    private data class Fixture(val creator: Long, val publisher: Long, val bunchId: Long, val bundleId: Long)
}
