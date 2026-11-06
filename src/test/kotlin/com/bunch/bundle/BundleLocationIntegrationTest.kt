package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleLocationRepository
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleLocationInput
import com.bunch.bundle.dto.BundleLocationState
import com.bunch.bundle.dto.BundleLocationsRequest
import com.bunch.bundle.dto.BundleTextRequest
import com.bunch.bundle.dto.BundleVisibilityRequest
import com.bunch.bundle.dto.PublishBundleRequest
import com.bunch.bundle.service.BundleAccessService
import com.bunch.bundle.service.BundleLeaseService
import com.bunch.bundle.service.BundleLocationService
import com.bunch.bundle.service.BundleLocationsRemovedEvent
import com.bunch.bundle.service.BundleService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.ProcessingStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BundleLocationIntegrationTest.EventConfiguration::class)
class BundleLocationIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var locations: BundleLocationService
    @Autowired
    private lateinit var rows: BundleLocationRepository
    @Autowired
    private lateinit var bundles: BundleRepository
    @Autowired
    private lateinit var participants: BundleParticipantRepository
    @Autowired
    private lateinit var access: BundleAccessService
    @Autowired
    private lateinit var leases: BundleLeaseService
    @Autowired
    private lateinit var bundleService: BundleService
    @Autowired
    private lateinit var transactions: PlatformTransactionManager
    @Autowired
    private lateinit var recorder: RemovalRecorder
    @Autowired
    private lateinit var mvc: MockMvc
    @Autowired
    private lateinit var mapper: ObjectMapper
    @Autowired
    private lateinit var tokens: TokenProvider
    @Autowired
    private lateinit var sessions: RefreshTokenRepository
    @Autowired
    private lateinit var assets: ImageAssetRepository
    @Autowired
    private lateinit var photos: PhotoRepository
    @Autowired
    private lateinit var bunchRows: BunchRepository

    private val credentials = mutableListOf<Pair<Long, String>>()
    private val assetIds = mutableListOf<Long>()

    @BeforeEach
    fun resetRecorder() {
        recorder.events.clear()
        recorder.reject = false
    }

    @AfterEach
    fun cleanBundles() {
        recorder.reject = false
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        assetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        credentials.forEach { (memberId, sid) -> sessions.delete(sid, memberId) }
    }

    @Test
    fun `saves zero to three normalized ordered locations and exposes them in snapshots`() {
        val f = fixture()
        val initial = bundleService.get(f.owner.memberId, f.bunchId, f.bundleId)
        assertThat(initial.locations).isEmpty()
        assertThat(initial.locationVersion).isZero()
        val state = save(f, listOf(place("c", 2), place("a", 0).copy(
            provider = " KAKAO ", externalId = " a ", name = " 애월 👨‍👩‍👧‍👦 ", address = " 제주 "
        ), place("b", 1)))
        assertThat(state.locations.map { it.externalId }).containsExactly("a", "b", "c")
        assertThat(state.locations.first().provider).isEqualTo("kakao")
        assertThat(state.locations.first().name).isEqualTo("애월 👨‍👩‍👧‍👦")
        assertThat(state.locations.first().address).isEqualTo("제주")
        assertThat(state.locationVersion).isEqualTo(1)
        assertThat(state.revision).isEqualTo(initial.revision + 1)
        val snapshot = bundleService.get(f.owner.memberId, f.bunchId, f.bundleId)
        assertThat(snapshot.locations).isEqualTo(state.locations)
        assertThat(snapshot.locationVersion).isEqualTo(state.locationVersion)
        val cleared = save(f, emptyList(), 1)
        assertThat(cleared.locations).isEmpty()
        assertThat(cleared.locationVersion).isEqualTo(2)
    }

    @Test
    fun `rejects oversized duplicate sparse and invalid positions without partial writes`() {
        val f = fixture()
        val invalid = listOf(
            (0..3).map { place("$it", it) },
            listOf(place("a", 0), place("a", 1).copy(provider = " KAKAO ")),
            listOf(place("a", 1)),
            listOf(place("a", -1)),
            listOf(place("a", 0), place("b", 0))
        )
        invalid.forEach { value -> assertError(ErrorCode.INVALID_INPUT) { save(f, value) } }
        assertThat(rows.findAllByBundleIdOrderByPositionAscIdAsc(f.bundleId)).isEmpty()
        assertThat(bundles.findById(f.bundleId).orElseThrow().locationVersion).isZero()
    }

    @Test
    fun `preserves source IDs through reorder metadata changes and normalized no ops`() {
        val f = fixture()
        val first = save(f, listOf(place("a", 0), place("b", 1)))
        val ids = first.locations.associate { it.externalId to it.id }
        val reordered = save(f, listOf(place("b", 0), place("a", 1).copy(name = "new name")), 1)
        assertThat(reordered.locations.map { it.id }).containsExactly(ids.getValue("b"), ids.getValue("a"))
        assertThat(recorder.events).isEmpty()
        val updatedAt = bundles.findById(f.bundleId).orElseThrow().updatedAt
        val noOp = save(f, listOf(place("a", 1).copy(name = " new name "), place("b", 0)), 2)
        assertThat(noOp).isEqualTo(reordered)
        assertThat(bundles.findById(f.bundleId).orElseThrow().updatedAt).isEqualTo(updatedAt)
    }

    @Test
    fun `replacement notifies only removed source IDs before deleting rows within the transaction`() {
        val f = fixture()
        val first = save(f, listOf(place("a", 0), place("b", 1)))
        val removed = first.locations.first().id
        val next = save(f, listOf(place("b", 0), place("c", 1)), 1)
        assertThat(next.locations.first().id).isEqualTo(first.locations.last().id)
        assertThat(recorder.events).containsExactly(BundleLocationsRemovedEvent(f.bundleId, listOf(removed)))
        assertThat(rows.existsById(removed)).isFalse()
    }

    @Test
    fun `synchronous listener failure rolls back locations versions and revisions`() {
        val f = fixture()
        val first = save(f, listOf(place("a", 0), place("b", 1)))
        recorder.reject = true
        assertThatThrownBy { save(f, listOf(place("c", 0)), 1) }
            .isInstanceOf(IllegalStateException::class.java)
        recorder.reject = false
        assertThat(locations.get(f.owner.memberId, f.bunchId, f.bundleId)).isEqualTo(first)
    }

    @Test
    fun `requires draft participation active membership and the matching bunch path`() {
        val f = fixture()
        val other = member()
        join(other, issue(f.owner.memberId, f.bunchId))
        assertError(ErrorCode.FORBIDDEN) {
            locations.saveDraft(BundleActor(other, UUID.randomUUID().toString()), f.bunchId, f.bundleId,
                BundleLocationsRequest(listOf(place("a", 0)), 0, f.token), "nonparticipant")
        }
        val stranger = member()
        assertError(ErrorCode.FORBIDDEN) {
            locations.saveDraft(BundleActor(stranger, UUID.randomUUID().toString()), f.bunchId, f.bundleId,
                BundleLocationsRequest(emptyList(), 0, f.token), "stranger")
        }
        val another = create(f.owner.memberId).id
        assertError(ErrorCode.RESOURCE_NOT_FOUND) {
            locations.saveDraft(f.owner, another, f.bundleId,
                BundleLocationsRequest(emptyList(), 0, f.token), "wrong-bunch")
        }
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ? AND member_id = ?",
            f.bunchId, f.guest.memberId)
        assertError(ErrorCode.FORBIDDEN) {
            locations.saveDraft(f.guest, f.bunchId, f.bundleId,
                BundleLocationsRequest(emptyList(), 0, f.token), "departed")
        }
    }

    @Test
    fun `requires lease token ownership freshness and optimistic location version`() {
        val f = fixture()
        fun rejected(actor: BundleActor, token: UUID?) = assertError(ErrorCode.BUNDLE_LEASE_CONFLICT) {
            locations.saveDraft(actor, f.bunchId, f.bundleId,
                BundleLocationsRequest(listOf(place("a", 0)), 0, token), "lease")
        }
        rejected(f.owner, null)
        rejected(f.owner, UUID.randomUUID())
        rejected(f.owner.copy(sessionId = UUID.randomUUID().toString()), f.token)
        rejected(f.guest, f.token)
        val first = save(f, listOf(place("a", 0)))
        assertError(ErrorCode.BUNDLE_VERSION_CONFLICT) { save(f, emptyList()) }
        assertThat(locations.get(f.owner.memberId, f.bunchId, f.bundleId)).isEqualTo(first)
        leases.release(f.owner, f.bunchId, f.bundleId, "LOCATION", f.token, "owner-location")
        val granted = leases.acquire(f.owner, f.bunchId, f.bundleId, "LOCATION", "new-location")
        rejected(f.owner, f.token)
        jdbc.update("UPDATE bundle_edit_leases SET last_heartbeat_at = ? WHERE bundle_id = ? AND target = 'LOCATION'",
            java.sql.Timestamp.from(Instant.now().minusSeconds(16)), f.bundleId)
        rejected(f.owner, granted.token)
    }

    @Test
    fun `concurrent saves with one expected version commit exactly once`() {
        val f = fixture()
        val results = concurrently(listOf(
            { runCatching { save(f, listOf(place("a", 0))) } },
            { runCatching { save(f, listOf(place("b", 0))) } }
        )).map { it.getOrThrow() }
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        val rejected = results.single { it.isFailure }.exceptionOrNull()
        assertThat(rejected).isInstanceOf(com.bunch.global.exception.BusinessException::class.java)
        assertThat((rejected as com.bunch.global.exception.BusinessException).errorCode)
            .isEqualTo(ErrorCode.BUNDLE_VERSION_CONFLICT)
        assertThat(bundles.findById(f.bundleId).orElseThrow().locationVersion).isEqualTo(1)
    }

    @Test
    fun `published standalone writes are rejected and locked editor participates without changing aggregate revision`() {
        val f = fixture()
        val first = save(f, listOf(place("a", 0)))
        jdbc.update("UPDATE bundles SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE id = ?", f.bundleId)
        assertError(ErrorCode.BUNDLE_STATE_CONFLICT) { save(f, emptyList(), 1) }
        val published = bundles.findById(f.bundleId).orElseThrow()
        assertThatThrownBy { locations.replaceLocked(published, emptyList()) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
        TransactionTemplate(transactions).executeWithoutResult {
            val locked = access.lock(f.owner.memberId, f.bunchId, f.bundleId)
            access.requireOwner(f.owner.memberId, f.bunchId)
            val changed = locations.replaceLocked(locked, listOf(place("b", 0)))
            assertThat(changed.changed).isTrue()
            assertThat(locked.locationVersion).isEqualTo(2)
            assertThat(locked.revision).isEqualTo(first.revision)
            assertThat(locked.publishedAt).isEqualTo(published.publishedAt)
        }
        val beforeRollback = rows.findAllByBundleIdOrderByPositionAscIdAsc(f.bundleId).single().id
        TransactionTemplate(transactions).executeWithoutResult { tx ->
            val locked = access.lock(f.owner.memberId, f.bunchId, f.bundleId)
            locations.replaceLocked(locked, emptyList())
            tx.setRollbackOnly()
        }
        assertThat(rows.findAllByBundleIdOrderByPositionAscIdAsc(f.bundleId).single().id).isEqualTo(beforeRollback)
    }

    @Test
    fun `batch query provides ordered results with empty entries and rejects unbounded or invalid inputs`() {
        val f = fixture()
        val state = save(f, listOf(place("a", 0), place("b", 1)))
        assertThat(locations.listForBundles(listOf(f.bundleId, Long.MAX_VALUE)))
            .containsEntry(f.bundleId, state.locations).containsEntry(Long.MAX_VALUE, emptyList())
        assertThat(locations.listForBundles(emptyList())).isEmpty()
        assertError(ErrorCode.INVALID_INPUT) { locations.listForBundles(listOf(0)) }
        assertError(ErrorCode.INVALID_INPUT) { locations.listForBundles((1L..501L).toList()) }
    }

    @Test
    fun `bundle deletion cascades locations without emitting the edit removal event`() {
        val f = fixture()
        val source = save(f, listOf(place("a", 0))).locations.single().id
        bundleService.delete(f.owner, f.bunchId, f.bundleId, "delete-bundle")
        assertThat(rows.existsById(source)).isFalse()
        assertThat(recorder.events).isEmpty()
    }

    @Test
    fun `HTTP validates payload auth and conflict detail and published reads use feed policy`() {
        val f = fixture()
        val bearer = credential(f.owner)
        val path = "/api/v1/bunches/${f.bunchId}/bundles/${f.bundleId}/locations"
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        mvc.perform(put(path).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
        val invalidBodies = listOf(
            """{"locations": [], "leaseToken": "${f.token}"}""",
            """{"locations": [], "expectedVersion": null, "leaseToken": "${f.token}"}""",
            """{"locations": [{"provider":"kakao","externalId":"a","name":"a","address":"b"}],
                "expectedVersion": 0, "leaseToken": "${f.token}"}""",
            """{"locations": [{"provider":"kakao","externalId":"a","name":"a","address":"b","position":null}],
                "expectedVersion": 0, "leaseToken": "${f.token}"}"""
        )
        invalidBodies.forEach { body ->
            mvc.perform(put(path).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
        }
        val body = BundleLocationsRequest(listOf(place("a", 0)), 0, f.token)
        mvc.perform(put(path).header("Authorization", bearer).header("X-Request-Id", UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.locations[0].externalId").value("a"))
        mvc.perform(put(path).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(body)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("BUNDLE_003"))
            .andExpect(jsonPath("$.error.detail.version").value(1))
            .andExpect(jsonPath("$.error.detail.locations[0].externalId").value("a"))
        mvc.perform(get(path).header("Authorization", bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.locationVersion").value(1))
        jdbc.update("UPDATE bundles SET status = 'PUBLISHED', visibility = 'PUBLIC', published_at = CURRENT_TIMESTAMP WHERE id = ?",
            f.bundleId)
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", f.bunchId)
        val viewer = BundleActor(member(), UUID.randomUUID().toString())
        assertThat(locations.get(viewer.memberId, f.bunchId, f.bundleId).locations).hasSize(1)
        jdbc.update("INSERT INTO feed_content_hides(target_kind, target_id, reason) VALUES ('BUNDLE', ?, 'test')", f.bundleId)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { locations.get(viewer.memberId, f.bunchId, f.bundleId) }
    }

    @Test
    fun `locations are optional for publication and are preserved with title and photo content events`() {
        val f = fixture()
        val asset = assets.saveAndFlush(ImageAsset(publicId = "location/${UUID.randomUUID()}", url = "https://test/a.jpg"))
        assetIds.add(asset.id)
        val photo = photos.saveAndFlush(Photo(
            bunch = bunchRows.findById(f.bunchId).orElseThrow(), imageAsset = asset,
            fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2), processingStatus = ProcessingStatus.READY
        ))
        jdbc.update("""INSERT INTO bundle_photos(bundle_id, photo_id, image_asset_id, position, added_by, added_at)
            VALUES (?, ?, ?, 0, ?, CURRENT_TIMESTAMP)""", f.bundleId, photo.id, asset.id, f.owner.memberId)
        val title = leases.acquire(f.owner, f.bunchId, f.bundleId, "TITLE", "publish-title")
        bundleService.title(f.owner, f.bunchId, f.bundleId,
            BundleTextRequest("publishable", 0, BundleStatus.DRAFT, title.token), "title")
        bundleService.visibility(f.owner, f.bunchId, f.bundleId,
            BundleVisibilityRequest(Visibility.PUBLIC, 0, BundleStatus.DRAFT), "visibility")
        listOf(f.owner, f.guest).forEach {
            bundleService.comment(it, f.bunchId, f.bundleId, BundleTextRequest("comment", 0, BundleStatus.DRAFT), "comment")
        }
        val ready = bundleService.get(f.owner.memberId, f.bunchId, f.bundleId)
        assertThat(ready.publishBlockers).isEmpty()
        val published = bundleService.publish(f.owner, f.bunchId, f.bundleId, UUID.randomUUID(), PublishBundleRequest(ready.revision))
        assertThat(published.locations).isEmpty()
        assertThat(published.title).isEqualTo("publishable")
        assertThat(published.photos.single().photoId).isEqualTo(photo.id)
        assertThat(published.leases).isEmpty()
    }

    private fun fixture(): Fixture {
        val owner = BundleActor(member(), UUID.randomUUID().toString())
        val guest = BundleActor(member(), UUID.randomUUID().toString())
        val bunchId = create(owner.memberId).id
        join(guest.memberId, issue(owner.memberId, bunchId))
        val bundle = bundles.saveAndFlush(Bundle(bunchId = bunchId, createdBy = owner.memberId))
        listOf(owner, guest).forEach {
            participants.saveAndFlush(BundleParticipant(bundleId = bundle.id, memberId = it.memberId))
        }
        val token = leases.acquire(owner, bunchId, bundle.id, "LOCATION", "owner-location").token
        return Fixture(owner, guest, bunchId, bundle.id, token)
    }

    private fun save(f: Fixture, input: List<BundleLocationInput>, version: Long = 0): BundleLocationState =
        locations.saveDraft(f.owner, f.bunchId, f.bundleId, BundleLocationsRequest(input, version, f.token), "save")

    private fun place(id: String, position: Int) = BundleLocationInput("kakao", id, "place $id", "address $id", position)

    private fun credential(actor: BundleActor): String {
        val pair = tokens.generateToken(actor.memberId, "ROLE_USER", actor.sessionId)
        sessions.save(actor.sessionId, actor.memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        credentials.add(actor.memberId to actor.sessionId)
        return "Bearer ${pair.accessToken}"
    }

    private data class Fixture(
        val owner: BundleActor,
        val guest: BundleActor,
        val bunchId: Long,
        val bundleId: Long,
        val token: UUID
    )

    @TestConfiguration
    class EventConfiguration {
        @Bean
        fun removalRecorder(rows: BundleLocationRepository) = RemovalRecorder(rows)
    }

    class RemovalRecorder(private val rows: BundleLocationRepository) {
        val events = CopyOnWriteArrayList<BundleLocationsRemovedEvent>()
        var reject = false

        @EventListener
        fun removed(event: BundleLocationsRemovedEvent) {
            check(TransactionSynchronizationManager.isActualTransactionActive())
            check(event.locationIds.all(rows::existsById))
            events.add(event)
            if (reject) error("listener failure")
        }
    }
}
