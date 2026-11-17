package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleTextRequest
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.dto.EditPublishedBundleRequest
import com.bunch.bundle.dto.PublishBundleRequest
import com.bunch.bundle.dto.PublishedBundlePhotoInput
import com.bunch.bundle.service.BundleService
import com.bunch.bundle.service.BundleProfanityRecheckWorker
import com.bunch.bundle.service.TitleCrdtService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.profanity.ProfanityDictionary
import com.bunch.global.profanity.ProfanityFilter
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.ProcessingStatus
import com.bunch.photo.service.PhotoService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doAnswer
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.eq
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest
@ActiveProfiles("test")
class BundleProfanityIntegrationTest : BunchIntegrationSupport() {
    @Autowired private lateinit var service: BundleService
    @Autowired private lateinit var rechecks: BundleProfanityRecheckWorker
    @Autowired private lateinit var crdt: TitleCrdtService
    @Autowired private lateinit var bundleRows: BundleRepository
    @Autowired private lateinit var bunchRows: BunchRepository
    @Autowired private lateinit var assets: ImageAssetRepository
    @Autowired private lateinit var photos: PhotoRepository
    @MockitoSpyBean private lateinit var profanity: ProfanityFilter
    @MockitoSpyBean private lateinit var photoService: PhotoService
    private val assetIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS profanity_recheck_failure ON bundles")
        jdbc.execute("DROP FUNCTION IF EXISTS profanity_recheck_failure()")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        assetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
    }

    @Test
    fun `draft title and member comment save violations but final publish rejects every field atomically`() {
        val f = fixture()
        val title = title(f, "씨.발")
        val saved = comment(f, f.guest, "F.u.c.k")
        comment(f, f.owner, "함께 기록")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type='DRAFT_READY_TO_PUBLISH'",
            Long::class.java)).isZero()
        assertThat(saved.title).isEqualTo("씨.발")
        assertThat(saved.profanity!!.titleHasProhibitedWord).isTrue()
        assertThat(saved.profanity!!.comments.single { it.memberId == f.guest }.hasProhibitedWord).isTrue()
        assertThat(saved.publishBlockers).contains("TITLE_PROHIBITED_WORD", "COMMENTS_PROHIBITED_WORD")
        assertThat(jdbc.queryForObject("SELECT title_has_prohibited_word FROM bundles WHERE id=?",
            Boolean::class.java, f.id)).isTrue()
        assertThat(jdbc.queryForObject("SELECT has_prohibited_word FROM bundle_comments WHERE bundle_id=? AND member_id=?",
            Boolean::class.java, f.id, f.guest)).isTrue()
        val before = service.get(f.owner, f.bunch, f.id)
        val error = assertThrows<BusinessException> {
            service.publish(actor(f.owner), f.bunch, f.id, UUID.randomUUID(),
                PublishBundleRequest(visibility = Visibility.PUBLIC))
        }
        assertThat(error.errorCode).isEqualTo(ErrorCode.PROHIBITED_WORD)
        assertThat(error.detail).isEqualTo(mapOf("fields" to listOf("title", "comments"),
            "commentMemberIds" to listOf(f.guest)))
        val after = service.get(f.owner, f.bunch, f.id)
        assertThat(after.status).isEqualTo(BundleStatus.DRAFT)
        assertThat(after.revision).isEqualTo(before.revision)
        assertThat(after.visibility).isEqualTo(before.visibility)
        assertThat(after.updatedAt).isEqualTo(before.updatedAt)
        title(f, "정상 여행", title.titleVersion)
        comment(f, f.guest, "즐거운 여행", saved.comments.single { it.memberId == f.guest }.version)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type='DRAFT_READY_TO_PUBLISH'",
            Long::class.java)).isEqualTo(1L)
        assertThat(service.publish(actor(f.owner), f.bunch, f.id, UUID.randomUUID(),
            PublishBundleRequest(visibility = Visibility.PUBLIC)).status).isEqualTo(BundleStatus.PUBLISHED)
    }

    @Test
    fun `CRDT merged final title is inspected without rewriting binary and no-op keeps revision`() {
        val f = fixture()
        val before = service.get(f.owner, f.bunch, f.id)
        val incoming = crdt.rebase(before.titleCrdtState, "시.발").state
        val merged = service.mergeTitle(actor(f.owner), f.bunch, f.id, incoming, "crdt-profanity")
        assertThat(merged.title).isEqualTo("시.발")
        assertThat(merged.profanity.titleHasProhibitedWord).isTrue()
        assertThat(merged.publishBlockers).contains("TITLE_PROHIBITED_WORD")
        assertThat(crdt.read(merged.titleCrdtState).value).isEqualTo("시.발")
        val current = service.get(f.owner, f.bunch, f.id)
        service.mergeTitle(actor(f.owner), f.bunch, f.id, merged.titleCrdtState, "crdt-repeat")
        assertThat(service.get(f.owner, f.bunch, f.id).revision).isEqualTo(current.revision)
    }

    @Test
    fun `latest dictionary is used for pending draft reads and publish despite persisted clean flags`() {
        val f = fixture()
        val saved = title(f, "여행기록")
        assertThat(saved.profanity!!.titleHasProhibitedWord).isFalse()
        val dictionary = ProfanityDictionary(listOf("여행기록"))
        doReturn(dictionary).`when`(profanity).current()
        val read = service.get(f.owner, f.bunch, f.id)
        assertThat(read.profanity!!.dictionaryVersion).isEqualTo(dictionary.version)
        assertThat(read.profanity!!.titleHasProhibitedWord).isTrue()
        assertThat(jdbc.queryForObject("SELECT profanity_dictionary_version FROM bundles WHERE id=?",
            String::class.java, f.id)).isNotEqualTo(dictionary.version)
        val error = assertThrows<BusinessException> {
            service.publish(actor(f.owner), f.bunch, f.id, UUID.randomUUID(), PublishBundleRequest())
        }
        assertThat(error.errorCode).isEqualTo(ErrorCode.PROHIBITED_WORD)
        assertThat(error.detail).isEqualTo(mapOf("fields" to listOf("title")))
    }

    @Test
    fun `removed participant stops blocking and cannot overwrite its deleted comment`() {
        val f = fixture()
        val extra = member()
        join(extra, issue(f.owner, f.bunch))
        service.addParticipant(actor(f.owner), f.bunch, f.id, extra, "add")
        val before = service.get(f.owner, f.bunch, f.id)
        val row = before.comments.single { it.memberId == extra }
        comment(f, extra, "씨발", row.version)
        val removed = service.removeParticipant(actor(f.owner), f.bunch, f.id, extra, "remove")
        assertThat(removed.profanity!!.comments.map { it.memberId }).doesNotContain(extra)
        assertThat(removed.publishBlockers).doesNotContain("COMMENTS_PROHIBITED_WORD")
        assertError(ErrorCode.FORBIDDEN) { comment(f, extra, "금지", row.version) }
    }

    @Test
    fun `published edit rejects whole request and successful publication replay survives new dictionary`() {
        val f = fixture()
        title(f, "여행기록")
        comment(f, f.owner, "첫 기록")
        comment(f, f.guest, "둘째 기록")
        val key = UUID.randomUUID()
        val request = PublishBundleRequest(visibility = Visibility.PUBLIC)
        val published = service.publish(actor(f.owner), f.bunch, f.id, key, request)
        val before = service.get(f.owner, f.bunch, f.id)
        val edit = EditPublishedBundleRequest(before.revision, "씨발", null,
            before.photos.map { PublishedBundlePhotoInput(bundlePhotoId = it.id) }, emptyList())
        assertError(ErrorCode.PROHIBITED_WORD) {
            service.editPublished(actor(f.owner), f.bunch, f.id, edit, "reject-edit")
        }
        assertThat(service.get(f.owner, f.bunch, f.id)).isEqualTo(before)
        doReturn(ProfanityDictionary(listOf("여행기록"))).`when`(profanity).current()
        assertThat(service.publish(actor(f.owner), f.bunch, f.id, key, request).revision).isEqualTo(published.revision)
        assertThat(service.get(f.owner, f.bunch, f.id).publishBlockers).isEmpty()
        assertThat(service.get(f.owner, f.bunch, f.id).title).isEqualTo("여행기록")
    }

    @Test
    fun `publish rechecks a policy replacement that occurs while locking source photos`() {
        val f = fixture()
        title(f, "여행기록")
        comment(f, f.owner, "첫 기록")
        comment(f, f.guest, "둘째 기록")
        val before = service.get(f.owner, f.bunch, f.id)
        replacePolicyWhenPhotosLock(f)

        assertError(ErrorCode.PROHIBITED_WORD) {
            service.publish(actor(f.owner), f.bunch, f.id, UUID.randomUUID(),
                PublishBundleRequest(visibility = Visibility.PUBLIC))
        }

        val after = service.get(f.owner, f.bunch, f.id)
        assertThat(after.status).isEqualTo(BundleStatus.DRAFT)
        assertThat(after.revision).isEqualTo(before.revision)
        assertThat(after.publishedAt).isNull()
    }

    @Test
    fun `published save rolls back photos and date if policy changes during source locking`() {
        val f = fixture()
        title(f, "여행기록")
        comment(f, f.owner, "첫 기록")
        comment(f, f.guest, "둘째 기록")
        val before = service.publish(actor(f.owner), f.bunch, f.id, UUID.randomUUID(),
            PublishBundleRequest(visibility = Visibility.PUBLIC))
        val extra = photo(f.bunch)
        replacePolicyWhenPhotosLock(f)
        val request = EditPublishedBundleRequest(before.revision, "여행기록", java.time.LocalDate.of(2024, 1, 2),
            before.photos.map { PublishedBundlePhotoInput(bundlePhotoId = it.id) } +
                PublishedBundlePhotoInput(photoId = extra.id), emptyList())

        assertError(ErrorCode.PROHIBITED_WORD) {
            service.editPublished(actor(f.owner), f.bunch, f.id, request, "late-policy")
        }

        val after = service.get(f.owner, f.bunch, f.id)
        assertThat(after.revision).isEqualTo(before.revision)
        assertThat(after.photos).isEqualTo(before.photos)
        assertThat(after.date).isEqualTo(before.date)
    }

    @Test
    fun `recheck changes only warning revision and later clearing or same policy stays idempotent`() {
        val f = fixture()
        title(f, "여행기록")
        comment(f, f.guest, "한줄기록")
        val before = service.get(f.owner, f.bunch, f.id)
        val timestamps = jdbc.queryForList(
            "SELECT member_id,last_activity_at FROM bunch_memberships WHERE bunch_id=? ORDER BY member_id", f.bunch)
        val notificationCount = count("notifications")
        val pushCount = count("push_jobs")
        val dictionary = ProfanityDictionary(listOf("여행기록", "한줄기록"))
        doReturn(dictionary).`when`(profanity).current()

        assertThat(rechecks.runOnce()).isEqualTo(1)

        val updated = service.get(f.owner, f.bunch, f.id)
        assertThat(updated.revision).isEqualTo(before.revision + 1)
        assertThat(updated.updatedAt).isEqualTo(before.updatedAt)
        assertThat(updated.titleVersion).isEqualTo(before.titleVersion)
        assertThat(updated.comments).isEqualTo(before.comments)
        assertThat(updated.profanity!!.dictionaryVersion).isEqualTo(dictionary.version)
        assertThat(jdbc.queryForObject("SELECT profanity_dictionary_version FROM bundles WHERE id=?",
            String::class.java, f.id)).isEqualTo(dictionary.version)
        assertThat(rechecks.runOnce()).isZero()
        assertThat(count("notifications")).isEqualTo(notificationCount)
        assertThat(count("push_jobs")).isEqualTo(pushCount)
        assertThat(jdbc.queryForList(
            "SELECT member_id,last_activity_at FROM bunch_memberships WHERE bunch_id=? ORDER BY member_id", f.bunch))
            .isEqualTo(timestamps)

        doReturn(ProfanityDictionary(emptyList())).`when`(profanity).current()
        assertThat(rechecks.runOnce()).isEqualTo(1)
        val cleared = service.get(f.owner, f.bunch, f.id)
        assertThat(cleared.revision).isEqualTo(updated.revision + 1)
        assertThat(cleared.publishBlockers).doesNotContain("TITLE_PROHIBITED_WORD", "COMMENTS_PROHIBITED_WORD")
        assertThat(cleared.updatedAt).isEqualTo(before.updatedAt)
    }

    @Test
    fun `hundred row batches advance past a failing draft and retry it after the scan`() {
        val f = fixture()
        title(f, "batchbad")
        jdbc.update("""INSERT INTO bundles(bunch_id,created_by,title,created_at,updated_at)
            SELECT ?,?,'batchbad',now(),now() FROM generate_series(1,100)""", f.bunch, f.owner)
        val dictionary = ProfanityDictionary(listOf("batchbad"))
        doReturn(dictionary).`when`(profanity).current()
        jdbc.execute("""CREATE FUNCTION profanity_recheck_failure() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN IF NEW.id = ${f.id} THEN RAISE EXCEPTION ''injected recheck failure''; END IF; RETURN NEW; END'""")
        jdbc.execute("""CREATE TRIGGER profanity_recheck_failure BEFORE UPDATE ON bundles
            FOR EACH ROW EXECUTE FUNCTION profanity_recheck_failure()""")

        assertThat(rechecks.runOnce()).isEqualTo(99)
        assertThat(rechecks.runOnce()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundles WHERE profanity_dictionary_version=?",
            Long::class.java, dictionary.version)).isEqualTo(100L)

        jdbc.execute("DROP TRIGGER profanity_recheck_failure ON bundles")
        jdbc.execute("DROP FUNCTION profanity_recheck_failure()")
        assertThat(rechecks.runOnce()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundles WHERE profanity_dictionary_version=?",
            Long::class.java, dictionary.version)).isEqualTo(101L)
        assertThat(rechecks.runOnce()).isZero()
    }

    @Test
    fun `legacy unexamined clean drafts are persisted without pretending user content changed`() {
        val f = fixture()
        jdbc.update("UPDATE bundles SET profanity_dictionary_version=NULL,title_has_prohibited_word=false WHERE id=?", f.id)
        val before = service.get(f.owner, f.bunch, f.id)
        assertThat(rechecks.runOnce()).isEqualTo(1)
        val after = service.get(f.owner, f.bunch, f.id)
        assertThat(after.revision).isEqualTo(before.revision)
        assertThat(after.updatedAt).isEqualTo(before.updatedAt)
        assertThat(jdbc.queryForObject("SELECT profanity_dictionary_version FROM bundles WHERE id=?",
            String::class.java, f.id)).isEqualTo(profanity.current().version)
    }

    @Test
    fun `publish and profane autosave serialize without ever publishing a prohibited title`() {
        val f = fixture()
        val clean = title(f, "여행기록")
        comment(f, f.owner, "첫 기록")
        comment(f, f.guest, "둘째 기록")
        val results = concurrently(listOf(
            { title(f, "씨발", clean.titleVersion) },
            { service.publish(actor(f.owner), f.bunch, f.id, UUID.randomUUID(),
                PublishBundleRequest(visibility = Visibility.PUBLIC)) }
        ))
        val current = service.get(f.owner, f.bunch, f.id)
        if (current.status == BundleStatus.PUBLISHED) {
            assertThat(current.title).isEqualTo("여행기록")
            assertThat(results[0].exceptionOrNull()).isInstanceOf(BusinessException::class.java)
        } else {
            assertThat(current.title).isEqualTo("씨발")
            assertThat((results[1].exceptionOrNull() as BusinessException).errorCode).isEqualTo(ErrorCode.PROHIBITED_WORD)
        }
    }

    private fun fixture(): Fixture {
        val owner = member()
        val guest = member()
        val bunch = create(owner).id
        join(guest, issue(owner, bunch))
        val photo = photo(bunch)
        val bundle = service.create(actor(owner), bunch, UUID.randomUUID(),
            CreateBundleRequest(listOf(photo.id), participantMemberIds = listOf(guest)))
        return Fixture(owner, guest, bunch, bundle.id)
    }

    private fun photo(bunch: Long): Photo {
        val asset = assets.saveAndFlush(ImageAsset(publicId = "profanity/${UUID.randomUUID()}",
            url = "https://example.test/profanity.jpg"))
        assetIds.add(asset.id)
        return photos.saveAndFlush(Photo(bunch = bunchRows.findById(bunch).orElseThrow(), imageAsset = asset,
            fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2), processingStatus = ProcessingStatus.READY))
    }

    private fun replacePolicyWhenPhotosLock(f: Fixture) {
        val dictionary = AtomicReference(ProfanityDictionary(emptyList()))
        org.mockito.Mockito.`when`(profanity.current()).thenAnswer { dictionary.get() }
        val target = AopTestUtils.getUltimateTargetObject<PhotoService>(photoService)
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            dictionary.set(ProfanityDictionary(listOf("여행기록")))
            result
        }.`when`(target).lockReadyPhotosForConnection(eq(f.bunch), anyCollection())
    }

    private fun title(f: Fixture, value: String, version: Long = 0) = service.title(
        actor(f.owner), f.bunch, f.id, BundleTextRequest(value, version, BundleStatus.DRAFT), UUID.randomUUID().toString()
    )

    private fun comment(f: Fixture, member: Long, value: String, version: Long = 0) = service.comment(
        actor(member), f.bunch, f.id, BundleTextRequest(value, version, BundleStatus.DRAFT), UUID.randomUUID().toString()
    )

    private fun actor(id: Long) = BundleActor(id, UUID.randomUUID().toString())
    private data class Fixture(val owner: Long, val guest: Long, val bunch: Long, val id: Long)
}
