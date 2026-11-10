package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchBlock
import com.bunch.bunch.domain.BunchBlockRepository
import com.bunch.bunch.domain.BunchProfileReport
import com.bunch.bunch.domain.BunchProfileReportRepository
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.service.BunchPublicService
import com.bunch.bunch.service.BunchSocialService
import com.bunch.bunch.dto.FollowDecision
import com.bunch.feed.service.FeedAccessService
import com.bunch.feed.service.FeedService
import com.bunch.feed.dto.FeedBundleItem
import com.bunch.bundle.domain.*
import com.bunch.bundle.dto.*
import com.bunch.bundle.dto.BundleLocationsRemovedEvent
import com.bunch.bundle.service.BundleService
import com.bunch.bundle.service.BundleAttributionService
import com.bunch.place.domain.PinnedPlaceRepository
import com.bunch.place.service.PinnedPlaceService
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.ProcessingStatus
import com.bunch.social.dto.CreateSocialCommentRequest
import com.bunch.social.service.SocialCommentService
import com.bunch.social.service.SocialLikeService
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
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PublishedBundleEditIntegrationTest.EventsConfiguration::class)
class PublishedBundleEditIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Autowired
    private lateinit var attribution: BundleAttributionService

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    private lateinit var pins: PinnedPlaceService

    @Autowired
    private lateinit var pinRows: PinnedPlaceRepository

    @Autowired
    private lateinit var blocks: BunchBlockRepository

    @Autowired
    private lateinit var reports: BunchProfileReportRepository

    @Autowired
    private lateinit var service: BundleService

    @Autowired
    private lateinit var publicService: BunchPublicService

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var bunchRows: BunchRepository

    @Autowired
    private lateinit var bundlePhotos: BundlePhotoRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var likes: SocialLikeService

    @Autowired
    private lateinit var socialComments: SocialCommentService

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @Autowired
    private lateinit var recorder: EditRecorder

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var feedAccess: FeedAccessService

    @Autowired
    private lateinit var feed: FeedService

    @Autowired
    private lateinit var social: BunchSocialService

    private val assetIds = mutableListOf<Long>()
    private val credentials = mutableListOf<Pair<Long, String>>()

    @BeforeEach
    fun reset() {
        recorder.edits.clear()
        recorder.visibilities.clear()
        recorder.removals.clear()
        recorder.rejectRemoval = false
    }

    @AfterEach
    fun clean() {
        recorder.rejectRemoval = false
        jdbc.execute("DROP TRIGGER IF EXISTS published_test_reject_photo ON bundle_photos")
        jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        jdbc.update("DELETE FROM feed_content_hides")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        assetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        credentials.forEach { (id, sid) -> sessions.delete(sid, id) }
    }

    @Test
    fun `full save updates all fields once and preserves published provenance participants and reactions`() {
        val f = fixture(2)
        val before = snapshot(f)
        val added = photo(f.bunchId)
        likes.like(f.guest, f.id)
        val social = socialComments.create(f.guest, f.id, UUID.randomUUID(), CreateSocialCommentRequest("social"))
        socialComments.delete(f.guest, f.id, social.id)
        val notificationCount = count("notifications")
        val pushCount = count("push_jobs")
        val request = edit(before).copy(
            title = " 바뀐 제목 ", date = LocalDate.of(2024, 2, 1),
            photos = listOf(
                PublishedBundlePhotoInput(bundlePhotoId = before.photos[1].id, caption = "새 캡션"),
                PublishedBundlePhotoInput(photoId = added.id, caption = "추가")
            ),
            locations = listOf(place("a", 0), place("b", 1), place("c", 2))
        )
        val saved = save(f, request)
        assertThat(saved.title).isEqualTo("바뀐 제목")
        assertThat(saved.visibility).isEqualTo(before.visibility)
        assertThat(saved.date).isEqualTo(request.date)
        assertThat(saved.revision).isEqualTo(before.revision + 1)
        assertThat(saved.titleVersion).isEqualTo(before.titleVersion + 1)
        assertThat(saved.visibilityVersion).isEqualTo(before.visibilityVersion)
        assertThat(saved.dateVersion).isEqualTo(before.dateVersion + 1)
        assertThat(saved.locationVersion).isEqualTo(1)
        assertThat(saved.photos.map { it.position }).containsExactly(0, 1)
        assertThat(saved.photos.map { it.caption }).containsExactly("새 캡션", "추가")
        assertThat(saved.photos.first().id).isEqualTo(before.photos[1].id)
        assertThat(saved.photos.first().addedAt).isEqualTo(before.photos[1].addedAt)
        assertThat(saved.photos.first().addedBy).isEqualTo(before.photos[1].addedBy)
        assertThat(saved.publishedAt).isEqualTo(before.publishedAt)
        assertThat(saved.participants).isEqualTo(before.participants)
        assertThat(saved.comments).isEqualTo(before.comments)
        assertThat(saved.likeCount).isEqualTo(1)
        assertThat(saved.likedByCurrentBunch).isTrue()
        assertThat(saved.commentCount).isEqualTo(1)
        assertThat(count("notifications")).isEqualTo(notificationCount)
        assertThat(count("push_jobs")).isEqualTo(pushCount)
        assertThat(photos.existsById(before.photos.first().photoId!!)).isTrue()
        assertThat(recorder.edits).hasSize(1)
        assertThat(recorder.edits.single().revision).isEqualTo(saved.revision)
        // Compare committed values: PostgreSQL rounds the response's nanoseconds to microseconds.
        val persisted = snapshot(f)
        val same = save(f, edit(saved))
        assertThat(same.revision).isEqualTo(saved.revision)
        assertThat(same).isEqualTo(persisted)
        assertThat(snapshot(f)).isEqualTo(persisted)
        assertThat(recorder.edits).hasSize(1)
        assertError(ErrorCode.BUNDLE_VERSION_CONFLICT) { save(f, request) }
    }

    @Test
    fun `retained photo survives source deletion and can change caption without original Photo`() {
        val f = fixture()
        val before = snapshot(f)
        jdbc.update("UPDATE bundle_photos SET photo_id = NULL WHERE bundle_id = ?", f.id)
        jdbc.update("DELETE FROM photos WHERE id = ?", before.photos.single().photoId)
        val saved = save(f, edit(snapshot(f)).copy(photos = listOf(
            PublishedBundlePhotoInput(bundlePhotoId = before.photos.single().id, caption = "보존된 사진"))))
        assertThat(saved.photos.single().photoId).isNull()
        assertThat(saved.photos.single().imageAssetId).isEqualTo(before.photos.single().imageAssetId)
        assertThat(saved.photos.single().caption).isEqualTo("보존된 사진")
    }

    @Test
    fun `concurrent saves reject a stale aggregate instead of merging partial content`() {
        val f = fixture()
        val request = edit(snapshot(f))
        val result = concurrently(listOf(
            { save(f, request.copy(title = "first", locations = listOf(place("first", 0)))) },
            { save(f, request.copy(title = "second", locations = listOf(place("second", 0)))) }
        ))
        assertThat(result.count { it.isSuccess }).isEqualTo(1)
        assertThat((result.single { it.isFailure }.exceptionOrNull() as BusinessException).errorCode)
            .isEqualTo(ErrorCode.BUNDLE_VERSION_CONFLICT)
        val after = snapshot(f)
        assertThat(after.locations.single().externalId).isEqualTo(after.title)
        assertThat(after.revision).isEqualTo(request.expectedRevision + 1)
        assertThat(recorder.edits).hasSize(1)
    }

    @Test
    fun `external readers see all old values until commit then all new values`() {
        val f = fixture()
        val reader = member()
        val before = publicService.bundle(reader, f.bunchId, f.id)
        val changed = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val writer = pool.submit {
                TransactionTemplate(transactions).execute {
                    save(f, edit(snapshot(f)).copy(title = "after commit", locations = listOf(place("new", 0))))
                    changed.countDown()
                    check(commit.await(15, TimeUnit.SECONDS))
                }
            }
            check(changed.await(10, TimeUnit.SECONDS))
            val during = publicService.bundle(reader, f.bunchId, f.id)
            assertThat(during).isEqualTo(before)
            assertThat(recorder.edits).isEmpty()
            commit.countDown()
            writer.get(15, TimeUnit.SECONDS)
            val after = publicService.bundle(reader, f.bunchId, f.id)
            assertThat(after.title).isEqualTo("after commit")
            assertThat(after.locations.single().externalId).isEqualTo("new")
            assertThat(after.revision).isEqualTo(before.revision + 1)
            assertThat(recorder.edits).hasSize(1)
        } finally {
            commit.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `late photo insert failure rolls back location changes removed photos and edited fields`() {
        val f = fixture(2)
        val initial = save(f, edit(snapshot(f)).copy(locations = listOf(place("original", 0))))
        val persisted = snapshot(f)
        val addition = photo(f.bunchId)
        recorder.edits.clear()
        recorder.visibilities.clear()
        rejectWrite("published_test_reject_photo", "bundle_photos", "INSERT")
        assertThatThrownBy {
            save(f, edit(initial).copy(title = "should roll back", photos = listOf(
                PublishedBundlePhotoInput(photoId = addition.id, caption = "added")),
                locations = listOf(place("replacement", 0))))
        }.rootCause().hasMessageContaining("injected bunch write failure")
        assertThat(snapshot(f)).isEqualTo(persisted)
        assertThat(recorder.edits).isEmpty()
        assertThat(assets.existsById(initial.photos.first().imageAssetId)).isTrue()
        assertThat(photos.existsById(initial.photos.first().photoId!!)).isTrue()
    }

    @Test
    fun `location reorder preserves IDs and removal listener failure rolls back entire edit`() {
        val f = fixture()
        val initial = save(f, edit(snapshot(f)).copy(locations = listOf(place("a", 0), place("b", 1))))
        val reordered = save(f, edit(initial).copy(locations = listOf(place("b", 0), place("a", 1))))
        val persisted = snapshot(f)
        assertThat(reordered.locations.map { it.id }).containsExactly(initial.locations[1].id, initial.locations[0].id)
        assertThat(recorder.removals).isEmpty()
        recorder.rejectRemoval = true
        assertThatThrownBy { save(f, edit(reordered).copy(title = "rollback", locations = emptyList())) }
            .hasMessageContaining("source listener failure")
        assertThat(snapshot(f)).isEqualTo(persisted)
        recorder.rejectRemoval = false
        val saved = save(f, edit(reordered).copy(locations = listOf(place("a", 0))))
        assertThat(saved.locations.single().id).isEqualTo(initial.locations[0].id)
        assertThat(recorder.removals.last().locationIds).containsExactly(initial.locations[1].id)
    }

    @Test
    fun `full save rejects zero or over twenty photos and supports exactly twenty`() {
        val f = fixture()
        val before = snapshot(f)
        assertError(ErrorCode.INVALID_INPUT) { save(f, edit(before).copy(photos = emptyList())) }
        val additions = (1..20).map { photo(f.bunchId) }
        val entries = additions.map { PublishedBundlePhotoInput(photoId = it.id, caption = "") }
        assertError(ErrorCode.INVALID_INPUT) {
            save(f, edit(before).copy(photos = edit(before).photos + entries))
        }
        val saved = save(f, edit(before).copy(photos = entries))
        assertThat(saved.photos.map { it.photoId }).containsExactlyElementsOf(additions.map { it.id })
        assertThat(saved.photos.map { it.position }).containsExactlyElementsOf((0..19).toList())
    }

    @Test
    fun `invalid photo identity ownership duplicates readiness and location limits never partially save`() {
        val f = fixture()
        val other = fixture()
        val before = snapshot(f)
        val row = before.photos.single()
        val originalId = row.photoId!!
        val notReady = photo(f.bunchId, ProcessingStatus.PENDING)
        val foreign = snapshot(other).photos.single()
        val requests = listOf(
            edit(before).copy(title = " "),
            edit(before).copy(title = "x".repeat(41)),
            edit(before).copy(title = "line\nbreak"),
            edit(before).copy(date = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1)),
            edit(before).copy(photos = listOf(PublishedBundlePhotoInput(caption = ""))),
            edit(before).copy(photos = listOf(PublishedBundlePhotoInput(row.id, originalId, ""))),
            edit(before).copy(photos = edit(before).photos + edit(before).photos),
            edit(before).copy(photos = listOf(PublishedBundlePhotoInput(bundlePhotoId = foreign.id, caption = ""))),
            edit(before).copy(photos = listOf(PublishedBundlePhotoInput(photoId = foreign.photoId, caption = ""))),
            edit(before).copy(photos = listOf(PublishedBundlePhotoInput(photoId = notReady.id, caption = ""))),
            edit(before).copy(photos = listOf(PublishedBundlePhotoInput(photoId = originalId, caption = ""))),
            edit(before).copy(photos = listOf(
                PublishedBundlePhotoInput(bundlePhotoId = row.id, caption = "x".repeat(201)))
            ),
            edit(before).copy(locations = (0..3).map { place("p$it", it) }),
            edit(before).copy(locations = listOf(place("a", 0), place("a", 1))),
            edit(before).copy(locations = listOf(place("a", 2)))
        )
        requests.forEach { request ->
            assertThatThrownBy { save(f, request) }.isInstanceOf(BusinessException::class.java)
            assertThat(snapshot(f)).isEqualTo(before)
        }
        assertThat(recorder.edits).isEmpty()
    }

    @Test
    fun `only current owner can save and roles are rechecked after waiting on bunch lock`() {
        val f = fixture()
        val before = snapshot(f)
        jdbc.update(
            "UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE member_id = ? AND bunch_id = ?",
            f.guest, f.bunchId
        )
        assertError(ErrorCode.FORBIDDEN) { save(f, edit(before), f.guest) }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val attempt = TransactionTemplate(transactions).execute {
                bunchRows.findLocked(f.bunchId)
                val pending = pool.submit<Throwable?> {
                    runCatching { save(f, edit(before).copy(title = "late owner")) }.exceptionOrNull()
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (true) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()")
                    val waiting = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",
                        Long::class.java)!!
                    if (waiting > 0) break
                    check(System.nanoTime() < deadline)
                    Thread.sleep(10)
                }
                jdbc.update(
                    "UPDATE bunch_memberships SET role='MEMBER' WHERE bunch_id=? AND member_id=?",
                    f.bunchId, f.owner
                )
                jdbc.update(
                    "UPDATE bunch_memberships SET role='OWNER' WHERE bunch_id=? AND member_id=?",
                    f.bunchId, f.guest
                )
                pending
            }!!
            assertThat((attempt.get(10, TimeUnit.SECONDS) as BusinessException).errorCode)
                .isEqualTo(ErrorCode.FORBIDDEN)
            assertThat(snapshot(f).title).isEqualTo(before.title)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `preloaded owner membership cannot edit delete or change scope after concurrent demotion`() {
        listOf("edit", "delete", "visibility").forEach { operation ->
            val f = fixture()
            val before = snapshot(f)
            val loaded = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val future = TransactionTemplate(transactions).execute {
                    bunchRows.findLocked(f.bunchId)
                    val pending = pool.submit<Throwable?> {
                        runCatching {
                            TransactionTemplate(transactions).execute {
                                val cached = memberships.findByMemberIdAndBunchIdAndStatus(f.owner, f.bunchId)!!
                                assertThat(cached.role.name).isEqualTo("OWNER")
                                bunchRows.findById(f.bunchId).orElseThrow().name
                                bundles.findById(f.id).orElseThrow().revision
                                loaded.countDown()
                                when (operation) {
                                    "delete" -> service.delete(
                                        BundleActor(f.owner, "s"), f.bunchId, f.id, "late-delete"
                                    )
                                    "visibility" -> visibility(f, Visibility.PRIVATE)
                                    else -> save(f, edit(before).copy(title = "preloaded owner write"))
                                }
                            }
                        }.exceptionOrNull()
                    }
                    check(loaded.await(5, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (true) {
                        jdbc.execute("SELECT pg_stat_clear_snapshot()")
                        val waiting = jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() " +
                                "AND wait_event_type='Lock'", Long::class.java
                        )!!
                        if (waiting > 0) break
                        check(System.nanoTime() < deadline)
                        Thread.sleep(10)
                    }
                    jdbc.update(
                        "UPDATE bunch_memberships SET role='MEMBER' WHERE bunch_id=? AND member_id=?",
                        f.bunchId, f.owner
                    )
                    jdbc.update(
                        "UPDATE bunch_memberships SET role='OWNER' WHERE bunch_id=? AND member_id=?",
                        f.bunchId, f.guest
                    )
                    pending
                }!!
                assertThat((future.get(10, TimeUnit.SECONDS) as? BusinessException)?.errorCode)
                    .isEqualTo(ErrorCode.FORBIDDEN)
                assertThat(bundles.existsById(f.id)).isTrue()
                assertThat(snapshot(f).title).isEqualTo(before.title)
                assertThat(snapshot(f).revision).isEqualTo(before.revision)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `targeted lock refresh preserves unrelated pending parent transaction changes`() {
        val f = fixture()
        val before = snapshot(f)
        val activity = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        TransactionTemplate(transactions).execute {
            val bunch = bunchRows.findById(f.bunchId).orElseThrow()
            val membership = memberships.findByMemberIdAndBunchIdAndStatus(f.owner, f.bunchId)!!
            bunch.introduction = "pending parent introduction"
            membership.lastActivityAt = activity
            save(f, edit(before).copy(title = "parent transaction save"))
        }
        assertThat(bunchRows.findById(f.bunchId).orElseThrow().introduction)
            .isEqualTo("pending parent introduction")
        assertThat(memberships.findByMemberIdAndBunchIdAndStatus(f.owner, f.bunchId)!!.lastActivityAt)
            .isEqualTo(activity.truncatedTo(java.time.temporal.ChronoUnit.MICROS))
    }

    @Test
    fun `published individual routes and participant comment mutations cannot bypass aggregate save`() {
        val f = fixture()
        val before = snapshot(f)
        val row = before.photos.single()
        val actor = BundleActor(f.owner, "owner")
        val calls = listOf<() -> Any?>(
            { service.title(actor, f.bunchId, f.id, BundleTextRequest("x", 0, BundleStatus.PUBLISHED), "title") },
            { service.date(actor, f.bunchId, f.id, BundleDateRequest(null, 0, BundleStatus.PUBLISHED), "date") },
            {
                service.caption(actor, f.bunchId, f.id, row.id,
                    BundleTextRequest("x", 0, BundleStatus.PUBLISHED), "caption")
            },
            {
                service.changePhotos(actor, f.bunchId, f.id, UUID.randomUUID(),
                    BundlePhotoChangesRequest(mode = BundleStatus.PUBLISHED))
            },
            {
                service.move(actor, f.bunchId, f.id, row.id,
                    MoveBundlePhotoRequest(mode = BundleStatus.PUBLISHED), "move")
            },
            { service.comment(actor, f.bunchId, f.id, BundleTextRequest("x", 0, BundleStatus.PUBLISHED), "comment") },
            { service.addParticipant(actor, f.bunchId, f.id, member(), "add") },
            { service.removeParticipant(actor, f.bunchId, f.id, f.guest, "remove") },
            { service.leave(actor, f.bunchId, f.id, "leave") }
        )
        calls.forEach { assertError(ErrorCode.BUNDLE_STATE_CONFLICT, it) }
        assertThat(snapshot(f)).isEqualTo(before)
    }

    @Test
    fun `departed authors are masked without changing upload order and same account rejoin restores content`() {
        val f = fixture()
        val before = snapshot(f)
        assertThat(before.comments.map { it.memberId }).containsExactly(f.owner, f.guest)
        jdbc.update("UPDATE bunch_memberships SET status='LEFT' WHERE bunch_id=? AND member_id=?", f.bunchId, f.guest)
        val masked = snapshot(f)
        assertThat(masked.participants.map { it.memberId }).containsExactly(f.owner, f.guest)
        assertThat(masked.participants.last().nickname).isEqualTo("작성자")
        assertThat(masked.participants.last().profileAvailable).isFalse()
        assertThat(masked.comments.last().content).isEqualTo("삭제된 코멘트입니다")
        val visitor = member()
        val public = publicService.bundle(visitor, f.bunchId, f.id)
        assertThat(public.contributors.last().profileAvailable).isFalse()
        assertThat(public.comments.last().content).isEqualTo("삭제된 코멘트입니다")
        val edited = save(f, edit(masked).copy(title = "departed participant allowed"))
        assertThat(edited.comments).isEqualTo(masked.comments)
        join(f.guest, issue(f.owner, f.bunchId))
        val restored = snapshot(f)
        assertThat(restored.participants).isEqualTo(before.participants)
        assertThat(restored.comments).isEqualTo(before.comments)
        mvc.perform(get("/api/v1/members/${f.guest}/social-profile").header("Authorization", credential(visitor)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.memberId").value(f.guest))
        jdbc.update("UPDATE members SET account_status='WITHDRAWN' WHERE id=?", f.guest)
        assertThat(snapshot(f).comments.last().content).isEqualTo("삭제된 코멘트입니다")
        assertThat(publicService.bundle(visitor, f.bunchId, f.id).contributors.last().nickname).isEqualTo("작성자")
        assertThat(jdbc.queryForObject("SELECT content FROM bundle_comments WHERE bundle_id=? AND member_id=?",
            String::class.java, f.id, f.guest)).isEqualTo("guest note")
    }

    @Test
    fun `internal get list and writes honor common operational hide policy`() {
        val f = fixture()
        val before = snapshot(f)
        jdbc.update("INSERT INTO feed_content_hides(target_kind,target_id,reason,created_at) VALUES ('BUNDLE',?,'test',now())", f.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { snapshot(f) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { save(f, edit(before)) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { visibility(f, Visibility.PRIVATE) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.delete(BundleActor(f.owner, "s"), f.bunchId, f.id, "delete") }
        assertThat(service.list(f.owner, f.bunchId, BundleStatus.PUBLISHED, 0, 20).total).isZero()
        jdbc.update("DELETE FROM feed_content_hides WHERE target_id=?", f.id)
        assertThat(snapshot(f).revision).isEqualTo(before.revision)
    }

    @Test
    fun `public card and detail use actual counts with deleted placeholders and current role permissions`() {
        val f = fixture()
        likes.like(f.owner, f.id)
        val note = socialComments.create(f.guest, f.id, UUID.randomUUID(), CreateSocialCommentRequest("actual social comment"))
        socialComments.delete(f.guest, f.id, note.id)
        val public = publicService.bundle(f.owner, f.bunchId, f.id)
        val card = publicService.bundles(f.owner, f.bunchId, 0, 20).items.single()
        assertThat(public.likeCount).isEqualTo(1)
        assertThat(public.likedByCurrentBunch).isTrue()
        assertThat(public.commentCount).isEqualTo(1)
        assertThat(card.likeCount).isEqualTo(public.likeCount)
        assertThat(card.likedByCurrentBunch).isTrue()
        assertThat(card.commentCount).isEqualTo(public.commentCount)
        assertThat(public.comments.map { it.content }).containsExactly("owner note", "guest note")
        assertThat(public.permissions.canEdit).isTrue()
        assertThat(public.permissions.canDelete).isTrue()
        assertThat(public.permissions.canReport).isFalse()
        val guest = publicService.bundle(f.guest, f.bunchId, f.id)
        assertThat(guest.permissions.canEdit).isFalse()
        assertThat(guest.permissions.canReport).isTrue()
        val zero = publicService.bundle(member(), f.bunchId, f.id)
        assertThat(zero.permissions.canLike).isFalse()
        assertThat(zero.permissions.canComment).isFalse()
        assertThat(zero.permissions.canReport).isFalse()
    }

    @Test
    fun `card and detail counts consistently filter blocked reported and hidden reaction sources`() {
        val f = fixture()
        val sources = (1..4).map {
            val actor = member()
            val source = create(actor).id
            likes.like(actor, f.id)
            socialComments.create(actor, f.id, UUID.randomUUID(), CreateSocialCommentRequest("source note"))
            source
        }
        assertThat(publicService.bundle(f.owner, f.bunchId, f.id).likeCount).isEqualTo(4)
        assertThat(publicService.bundle(f.owner, f.bunchId, f.id).commentCount).isEqualTo(4)
        blocks.saveAndFlush(BunchBlock(blockerBunchId = sources[0], blockedBunchId = f.bunchId,
            createdByMemberId = f.owner, createdAt = Instant.now()))
        reports.saveAndFlush(BunchProfileReport(reporterBunchId = f.bunchId, targetBunchId = sources[1],
            reportedByMemberId = f.owner, reason = BunchReportReason.SPAM, detail = null, createdAt = Instant.now()))
        jdbc.update("INSERT INTO feed_content_hides(target_kind,target_id,reason) VALUES ('BUNCH',?,'test')", sources[2])
        val detail = publicService.bundle(f.owner, f.bunchId, f.id)
        val card = publicService.bundles(f.owner, f.bunchId, 0, 20).items.single()
        assertThat(detail.likeCount).isEqualTo(1)
        assertThat(detail.commentCount).isEqualTo(1)
        assertThat(card.likeCount).isEqualTo(detail.likeCount)
        assertThat(card.commentCount).isEqualTo(detail.commentCount)
    }

    @Test
    fun `detail returns all eight uploaded participants without truncation in shared comment order`() {
        val f = fixture()
        val extras = (1..6).map { member().also { join(it, issue(f.owner, f.bunchId)) } }
        extras.forEachIndexed { index, id ->
            jdbc.update("INSERT INTO bundle_participants(bundle_id,member_id,joined_at) VALUES (?,?,?)",
                f.id, id, java.sql.Timestamp.from(Instant.now().plusSeconds(index.toLong())))
        }
        extras.reversed().forEach { id ->
            jdbc.update("INSERT INTO bundle_comments(bundle_id,member_id,content,created_at,updated_at) " +
                "VALUES (?,?,'extra note',now(),now())", f.id, id)
        }
        val expected = listOf(f.owner, f.guest) + extras
        val detail = publicService.bundle(f.owner, f.bunchId, f.id)
        assertThat(detail.contributors.map { it.memberId }).containsExactlyElementsOf(expected)
        assertThat(detail.comments.map { it.memberId }).containsExactlyElementsOf(expected)
        assertThat(detail.contributors).allMatch { it.profileAvailable }
        val snapshot = snapshot(f)
        assertThat(snapshot.participants.map { it.memberId }).containsExactlyElementsOf(expected)
        assertThat(snapshot.comments.map { it.memberId }).containsExactlyElementsOf(expected)
    }

    @Test
    fun `deletion cascades actual social rows preserves source photos and recalculates stage`() {
        val f = fixture()
        val before = snapshot(f)
        likes.like(f.owner, f.id)
        socialComments.create(f.guest, f.id, UUID.randomUUID(), CreateSocialCommentRequest("delete cascade"))
        service.delete(BundleActor(f.owner, "s"), f.bunchId, f.id, "delete")
        assertThat(bundles.existsById(f.id)).isFalse()
        assertThat(count("social_bundle_likes")).isZero()
        assertThat(count("social_comments")).isZero()
        assertThat(photos.existsById(before.photos.single().photoId!!)).isTrue()
        assertThat(service.summary(f.owner, f.bunchId).publishedCount).isZero()
        assertThat(recorder.removals).isEmpty()
    }

    @Test
    fun `published attribution batches fifty bundles including profile images in three queries`() {
        val f = fixture()
        val profileAsset = assets.saveAndFlush(ImageAsset(
            publicId = "profile/${UUID.randomUUID()}", url = "https://images.example/profile.jpg"
        ))
        assetIds.add(profileAsset.id)
        jdbc.update("UPDATE members SET profile_image_asset_id=? WHERE id=?", profileAsset.id, f.owner)
        val additional = (1..49).map {
            bundles.saveAndFlush(Bundle(bunchId = f.bunchId, createdBy = f.owner, status = BundleStatus.PUBLISHED,
                title = "batch", visibility = Visibility.PUBLIC))
        }
        additional.forEach { bundle ->
            jdbc.update("INSERT INTO bundle_participants(bundle_id,member_id,joined_at) VALUES (?,?,now())",
                bundle.id, f.owner)
        }
        val selected = listOf(bundles.findById(f.id).orElseThrow()) + additional
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        val previous = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
        statistics.clear()
        try {
            val result = attribution.publishedAll(selected)
            assertThat(result).hasSize(50)
            assertThat(result.getValue(f.id).participants).hasSize(2)
            assertThat(result.values).allSatisfy { value ->
                assertThat(value.participants.first().imageUrl).isEqualTo(profileAsset.url)
                assertThat(value.participants.first().profileAvailable).isTrue()
            }
            assertThat(statistics.prepareStatementCount).isEqualTo(3)
            assertThat(attribution.publishedAll(emptyList())).isEmpty()
            assertError(ErrorCode.INVALID_INPUT) { attribution.publishedAll(List(501) { selected.first() }) }
            assertError(ErrorCode.INVALID_INPUT) {
                attribution.publishedAll(listOf(Bundle(bunchId = f.bunchId, createdBy = f.owner)))
            }
        } finally {
            statistics.isStatisticsEnabled = previous
            jdbc.update("UPDATE members SET profile_image_asset_id=NULL WHERE id=?", f.owner)
        }
    }

    @Test
    fun `actual G listener removes only edited source pins and bundle deletion preserves remaining snapshots`() {
        val f = fixture()
        val initial = save(f, edit(snapshot(f)).copy(locations = listOf(place("a", 0), place("b", 1))))
        val actor = member()
        create(actor)
        val ownPin = pins.pinBundleLocation(f.owner, f.id, initial.locations[0].id)
        val externalPin = pins.pinBundleLocation(actor, f.id, initial.locations[0].id)
        val retainedPin = pins.pinBundleLocation(actor, f.id, initial.locations[1].id)
        val other = fixture()
        val otherLocated = save(other, edit(snapshot(other)).copy(locations = listOf(place("a", 0))))
        val differentSource = pins.pinBundleLocation(other.owner, other.id, otherLocated.locations.single().id)
        val edited = save(f, edit(initial).copy(locations = listOf(place("b", 0))))
        assertThat(pinRows.existsById(ownPin.id)).isFalse()
        assertThat(pinRows.existsById(externalPin.id)).isFalse()
        assertThat(pinRows.existsById(differentSource.id)).isTrue()
        assertThat(pinRows.findById(retainedPin.id).orElseThrow().sourceLocationId)
            .isEqualTo(edited.locations.single().id)
        val removalCount = recorder.removals.size
        service.delete(BundleActor(f.owner, "s"), f.bunchId, f.id, "delete")
        val preserved = pinRows.findById(retainedPin.id).orElseThrow()
        assertThat(preserved.sourceBundleId).isNull()
        assertThat(preserved.sourceLocationId).isNull()
        assertThat(preserved.name).isEqualTo(retainedPin.name)
        assertThat(preserved.address).isEqualTo(retainedPin.address)
        assertThat(preserved.pinnedAt).isEqualTo(retainedPin.pinnedAt)
        assertThat(recorder.removals).hasSize(removalCount)
    }

    @Test
    fun `actual pin removal rolls back with a subsequent failed photo insert in atomic published edit`() {
        val f = fixture()
        val initial = save(f, edit(snapshot(f)).copy(locations = listOf(place("source", 0))))
        val persisted = snapshot(f)
        val pin = pins.pinBundleLocation(f.owner, f.id, initial.locations.single().id)
        val addition = photo(f.bunchId)
        rejectWrite("published_test_reject_photo", "bundle_photos", "INSERT")
        assertThatThrownBy {
            save(f, edit(initial).copy(title = "failed edit", locations = emptyList(),
                photos = listOf(PublishedBundlePhotoInput(photoId = addition.id, caption = ""))))
        }.rootCause().hasMessageContaining("injected bunch write failure")
        assertThat(snapshot(f)).isEqualTo(persisted)
        val preserved = pinRows.findById(pin.id).orElseThrow()
        assertThat(preserved.sourceBundleId).isEqualTo(f.id)
        assertThat(preserved.sourceLocationId).isEqualTo(initial.locations.single().id)
    }

    @Test
    fun `HTTP aggregate save validates auth input paths versions and returns full typed detail`() {
        val f = fixture()
        val before = snapshot(f)
        val url = "/api/v1/bunches/${f.bunchId}/bundles/${f.id}/published"
        val payload = edit(before).copy(title = "HTTP", locations = listOf(place("http", 0)))
        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(payload)))
            .andExpect(status().isUnauthorized)
        mvc.perform(put(url).header("Authorization", credential(f.guest)).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(payload))).andExpect(status().isForbidden)
        val auth = credential(f.owner)
        mvc.perform(put(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
        mvc.perform(put(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(payload)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.title").value("HTTP"))
            .andExpect(jsonPath("$.data.locations[0].externalId").value("http"))
            .andExpect(jsonPath("$.data.permissions.canEdit").value(true))
        mvc.perform(put(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(payload)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.BUNDLE_VERSION_CONFLICT.code))
        assertError(ErrorCode.RESOURCE_NOT_FOUND) {
            service.editPublished(BundleActor(f.owner, "s"), create(f.owner).id, f.id, payload, "wrong-path")
        }
    }

    @Test
    fun `published visibility only changes scope and preserves content provenance reactions and notifications`() {
        val f = fixture(2)
        val before = snapshot(f)
        val visitor = member()
        create(visitor)
        likes.like(visitor, f.id)
        socialComments.create(visitor, f.id, UUID.randomUUID(), CreateSocialCommentRequest("external note"))
        val notifications = count("notifications")
        val pushes = count("push_jobs")
        val activity = memberships.findByMemberIdAndBunchIdAndStatus(f.owner, f.bunchId)!!.lastActivityAt
        assertThat(feedAccess.visibleBundleIds(visitor, listOf(f.id))).containsExactly(f.id)
        assertThat(feed.get(visitor).items.filterIsInstance<FeedBundleItem>().map { it.bundle.id }).contains(f.id)
        val saved = visibility(f, Visibility.PRIVATE)
        assertThat(saved.visibility).isEqualTo(Visibility.PRIVATE)
        assertThat(saved.visibilityVersion).isEqualTo(before.visibilityVersion + 1)
        assertThat(saved.revision).isEqualTo(before.revision + 1)
        assertThat(saved.updatedAt).isAfter(before.updatedAt)
        assertThat(saved.copy(
            visibility = before.visibility, visibilityVersion = before.visibilityVersion,
            revision = before.revision, updatedAt = before.updatedAt,
            likeCount = before.likeCount, commentCount = before.commentCount
        )).isEqualTo(before)
        assertThat(service.get(f.guest, f.bunchId, f.id).id).isEqualTo(f.id)
        assertThat(feedAccess.visibleBundleIds(visitor, listOf(f.id))).isEmpty()
        assertThat(feed.get(visitor).items.filterIsInstance<FeedBundleItem>().map { it.bundle.id }).doesNotContain(f.id)
        assertThat(publicService.bundles(visitor, f.bunchId, 0, 20).items).isEmpty()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { publicService.bundle(visitor, f.bunchId, f.id) }
        val persisted = snapshot(f)
        assertThat(visibility(f, Visibility.PRIVATE, saved.visibilityVersion)).isEqualTo(persisted)
        assertThat(recorder.visibilities).hasSize(1)
        assertError(ErrorCode.BUNDLE_VERSION_CONFLICT) { visibility(f, Visibility.PRIVATE) }
        val restored = visibility(f, Visibility.PUBLIC, saved.visibilityVersion)
        val external = publicService.bundle(visitor, f.bunchId, f.id)
        assertThat(feed.get(visitor).items.filterIsInstance<FeedBundleItem>().map { it.bundle.id }).contains(f.id)
        assertThat(external.likeCount).isEqualTo(1)
        assertThat(external.commentCount).isEqualTo(1)
        assertThat(external.publishedAt).isEqualTo(before.publishedAt)
        assertThat(restored.photos).isEqualTo(before.photos)
        assertThat(restored.comments).isEqualTo(before.comments)
        assertThat(count("notifications")).isEqualTo(notifications)
        assertThat(count("push_jobs")).isEqualTo(pushes)
        assertThat(memberships.findByMemberIdAndBunchIdAndStatus(f.owner, f.bunchId)!!.lastActivityAt)
            .isEqualTo(activity)
        assertThat(recorder.edits).isEmpty()
        assertThat(recorder.visibilities).hasSize(2)
    }

    @Test
    fun `published visibility honors private bunch approved followers after reopening`() {
        val f = fixture()
        jdbc.update("UPDATE bunches SET visibility='PRIVATE' WHERE id=?", f.bunchId)
        val visitor = member()
        val viewer = create(visitor).id
        val follow = social.follow(visitor, viewer, f.bunchId).followId!!
        assertError(ErrorCode.FORBIDDEN) { publicService.bundle(visitor, f.bunchId, f.id) }
        social.decide(f.owner, f.bunchId, follow, FollowDecision.APPROVE)
        assertThat(publicService.bundle(visitor, f.bunchId, f.id).id).isEqualTo(f.id)
        visibility(f, Visibility.PRIVATE)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { publicService.bundle(visitor, f.bunchId, f.id) }
        visibility(f, Visibility.PUBLIC, 1)
        assertThat(publicService.bundle(visitor, f.bunchId, f.id).id).isEqualTo(f.id)
        social.cancel(visitor, viewer, f.bunchId, follow)
        assertError(ErrorCode.FORBIDDEN) { publicService.bundle(visitor, f.bunchId, f.id) }
    }

    @Test
    fun `content save cannot change legacy scope input and only scope version governs scope writes`() {
        val f = fixture()
        val before = snapshot(f)
        val payload = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
            edit(before).copy(title = "content")
        )
        payload.put("visibility", "PRIVATE")
        mvc.perform(put("/api/v1/bunches/${f.bunchId}/bundles/${f.id}/published")
            .header("Authorization", credential(f.owner)).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(payload)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
            .andExpect(jsonPath("$.data.visibilityVersion").value(before.visibilityVersion))
        val scoped = visibility(f, Visibility.PRIVATE, before.visibilityVersion)
        assertThat(scoped.title).isEqualTo("content")
        assertError(ErrorCode.BUNDLE_VERSION_CONFLICT) { save(f, edit(before).copy(title = "stale content")) }
        val saved = save(f, edit(scoped).copy(title = "fresh content"))
        assertThat(saved.visibility).isEqualTo(Visibility.PRIVATE)
        assertThat(saved.visibilityVersion).isEqualTo(scoped.visibilityVersion)
    }

    @Test
    fun `concurrent content and scope saves never overwrite one another`() {
        repeat(3) {
            val f = fixture()
            val before = snapshot(f)
            val results = concurrently(listOf(
                { save(f, edit(before).copy(title = "concurrent content")) },
                { visibility(f, Visibility.PRIVATE, before.visibilityVersion) }
            ))
            assertThat(results[1].isSuccess).isTrue()
            val saved = snapshot(f)
            assertThat(saved.visibility).isEqualTo(Visibility.PRIVATE)
            if (results[0].isSuccess) {
                assertThat(saved.title).isEqualTo("concurrent content")
                assertThat(saved.revision).isEqualTo(before.revision + 2)
            } else {
                assertThat((results[0].exceptionOrNull() as BusinessException).errorCode)
                    .isEqualTo(ErrorCode.BUNDLE_VERSION_CONFLICT)
                assertThat(saved.title).isEqualTo(before.title)
                assertThat(saved.revision).isEqualTo(before.revision + 1)
            }
        }
    }

    @Test
    fun `scope HTTP requires owner state path version and sends a correlated commit event`() {
        val f = fixture()
        val before = snapshot(f)
        val url = "/api/v1/bunches/${f.bunchId}/bundles/${f.id}/visibility"
        val payload = mapper.writeValueAsBytes(BundleVisibilityRequest(Visibility.PRIVATE, 0, BundleStatus.PUBLISHED))
        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isUnauthorized)
        val auth = credential(f.owner)
        mvc.perform(put(url).header("Authorization", credential(f.guest)).contentType(MediaType.APPLICATION_JSON)
            .content(payload)).andExpect(status().isForbidden)
        val requestId = UUID.randomUUID().toString()
        mvc.perform(put(url).header("Authorization", auth).header("X-Request-Id", requestId)
            .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
        assertThat(recorder.visibilities.single().requestId).isEqualTo(requestId)
        assertThat(recorder.visibilities.single().revision).isEqualTo(before.revision + 1)
        mvc.perform(put(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.BUNDLE_VERSION_CONFLICT.code))
        assertError(ErrorCode.BUNDLE_STATE_CONFLICT) {
            service.visibility(BundleActor(f.owner, "s"), f.bunchId, f.id,
                BundleVisibilityRequest(Visibility.PUBLIC, 1, BundleStatus.DRAFT), "late-draft")
        }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) {
            service.visibility(BundleActor(f.owner, "s"), create(f.owner).id, f.id,
                BundleVisibilityRequest(Visibility.PUBLIC, 1, BundleStatus.PUBLISHED), "wrong-path")
        }
        jdbc.update("UPDATE bundles SET status='DRAFT', published_at=NULL WHERE id=?", f.id)
        assertError(ErrorCode.BUNDLE_STATE_CONFLICT) { visibility(f, Visibility.PUBLIC, 1) }
        jdbc.update("DELETE FROM bundles WHERE id=?", f.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { visibility(f, Visibility.PUBLIC, 1) }
    }

    @Test
    fun `scope HTTP requires an explicit non-null version and valid scope and mode`() {
        val f = fixture()
        val before = snapshot(f)
        val url = "/api/v1/bunches/${f.bunchId}/bundles/${f.id}/visibility"
        val auth = credential(f.owner)
        val payloads = listOf(
            """{"value":"PRIVATE","mode":"PUBLISHED"}""",
            """{"value":"PRIVATE","expectedVersion":null,"mode":"PUBLISHED"}""",
            """{"value":"PRIVATE","expectedVersion":0}""",
            """{"value":"ALL","expectedVersion":0,"mode":"PUBLISHED"}""",
            """{"value":null,"expectedVersion":0,"mode":"PUBLISHED"}"""
        )
        payloads.forEach { payload ->
            mvc.perform(put(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_JSON.code))
        }
        assertThat(snapshot(f)).isEqualTo(before)
        assertThat(recorder.visibilities).isEmpty()
    }

    @Test
    fun `scope authorization uses active owner role without requiring published participation`() {
        val f = fixture()
        jdbc.update("UPDATE bunch_memberships SET role='CO_OWNER' WHERE bunch_id=? AND member_id=?", f.bunchId, f.guest)
        assertError(ErrorCode.FORBIDDEN) {
            service.visibility(BundleActor(f.guest, "s"), f.bunchId, f.id,
                BundleVisibilityRequest(Visibility.PRIVATE, 0, BundleStatus.PUBLISHED), "co-owner")
        }
        jdbc.update("DELETE FROM bundle_participants WHERE bundle_id=? AND member_id=?", f.id, f.owner)
        assertThat(visibility(f, Visibility.PRIVATE).permissions.participant).isFalse()
        jdbc.update("UPDATE bunch_memberships SET status='LEFT' WHERE bunch_id=? AND member_id=?", f.bunchId, f.owner)
        assertError(ErrorCode.FORBIDDEN) { visibility(f, Visibility.PUBLIC, 1) }
    }

    @Test
    fun `visibility event is only delivered on commit and rollback retains the old scope`() {
        val f = fixture()
        val before = snapshot(f)
        TransactionTemplate(transactions).execute { tx ->
            visibility(f, Visibility.PRIVATE)
            assertThat(recorder.visibilities).isEmpty()
            tx.setRollbackOnly()
        }
        assertThat(snapshot(f)).isEqualTo(before)
        assertThat(recorder.visibilities).isEmpty()
    }

    private fun fixture(photoCount: Int = 1): Fixture {
        val owner = member()
        val guest = member()
        val bunchId = create(owner).id
        join(guest, issue(owner, bunchId))
        jdbc.update("UPDATE bunches SET visibility='PUBLIC' WHERE id=?", bunchId)
        val now = Instant.now().minusSeconds(3600)
        val bundle = bundles.saveAndFlush(Bundle(
            bunchId = bunchId,
            createdBy = owner,
            status = BundleStatus.PUBLISHED,
            title = "original",
            visibility = Visibility.PUBLIC,
            createdAt = now,
            updatedAt = now,
            publishedAt = now
        ))
        listOf(owner, guest).forEachIndexed { index, id ->
            jdbc.update("INSERT INTO bundle_participants(bundle_id,member_id,joined_at) VALUES (?,?,?)",
                bundle.id, id, java.sql.Timestamp.from(now.plusSeconds(index.toLong())))
        }
        listOf(guest to "guest note", owner to "owner note").forEach { (id, text) ->
            jdbc.update("INSERT INTO bundle_comments(bundle_id,member_id,content,created_at,updated_at) VALUES (?,?,?,now(),now())",
                bundle.id, id, text)
        }
        repeat(photoCount) { position ->
            val source = photo(bunchId)
            bundlePhotos.saveAndFlush(BundlePhoto(bundleId = bundle.id, photoId = source.id,
                imageAsset = source.imageAsset, position = position, addedBy = owner))
        }
        return Fixture(owner, guest, bunchId, bundle.id)
    }

    private fun photo(bunchId: Long, status: ProcessingStatus = ProcessingStatus.READY): Photo {
        val asset = assets.saveAndFlush(ImageAsset(
            publicId = "published/${UUID.randomUUID()}",
            url = "https://images.example/test.jpg"
        ))
        assetIds.add(asset.id)
        return photos.saveAndFlush(Photo(bunch = bunchRows.findById(bunchId).orElseThrow(), imageAsset = asset,
            fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2), processingStatus = status))
    }

    private fun visibility(f: Fixture, value: Visibility, version: Long = 0) = service.visibility(
        BundleActor(f.owner, "session-${f.owner}"), f.bunchId, f.id,
        BundleVisibilityRequest(value, version, BundleStatus.PUBLISHED), "published-visibility"
    )

    private fun snapshot(f: Fixture) = service.get(f.owner, f.bunchId, f.id)
    private fun save(f: Fixture, request: EditPublishedBundleRequest, actor: Long = f.owner) =
        service.editPublished(BundleActor(actor, "session-$actor"), f.bunchId, f.id, request, "published-edit")

    private fun edit(value: BundleSnapshot) = EditPublishedBundleRequest(
        value.revision, value.title, value.date,
        value.photos.map { PublishedBundlePhotoInput(bundlePhotoId = it.id, caption = it.caption) },
        value.locations.map { BundleLocationInput(it.provider, it.externalId, it.name, it.address, it.position) }
    )

    private fun credential(userId: Long): String {
        val sid = UUID.randomUUID().toString()
        val pair = tokens.generateToken(userId, "ROLE_USER", sid)
        sessions.save(sid, userId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        credentials.add(userId to sid)
        return "Bearer ${pair.accessToken}"
    }

    private fun place(id: String, position: Int) = BundleLocationInput("kakao", id, "place $id", "address $id", position)
    private data class Fixture(val owner: Long, val guest: Long, val bunchId: Long, val id: Long)

    @TestConfiguration
    class EventsConfiguration {
        @Bean
        fun editRecorder() = EditRecorder()

        @Bean
        @Primary
        // Reproduce Linux clock precision even when the host clock only supplies microseconds.
        fun nanosecondClock(): Clock = Clock.offset(
            Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000)),
            Duration.ofNanos(789)
        )
    }

    class EditRecorder {
        val edits = CopyOnWriteArrayList<BundleChangedEvent>()
        val visibilities = CopyOnWriteArrayList<BundleChangedEvent>()
        val removals = CopyOnWriteArrayList<BundleLocationsRemovedEvent>()
        var rejectRemoval = false

        @TransactionalEventListener
        fun edited(event: BundleChangedEvent) {
            if (event.type == BundleEventType.EDITED) edits.add(event)
            if (event.type == BundleEventType.VISIBILITY) visibilities.add(event)
        }

        @EventListener
        fun removed(event: BundleLocationsRemovedEvent) {
            removals.add(event)
            if (rejectRemoval) error("source listener failure")
        }
    }
}
