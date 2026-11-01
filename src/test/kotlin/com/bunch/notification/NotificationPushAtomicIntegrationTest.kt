package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundlePhotoChangesRequest
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.service.BundleService
import com.bunch.global.image.ProfileImageStorage
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.service.NotificationService
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class NotificationPushAtomicIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var bundleService: BundleService

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var uploaders: PhotoUploaderRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var inbox: NotificationService

    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    private val assetIds = mutableListOf<Long>()

    @AfterEach
    fun clearOwnFixtures() {
        jdbc.execute("DROP TRIGGER IF EXISTS atomic_push_reject ON push_jobs")
        jdbc.execute("DROP FUNCTION IF EXISTS atomic_push_reject_write()")
        jdbc.update("DELETE FROM push_jobs")
        jdbc.update("DELETE FROM push_devices")
        jdbc.update("DELETE FROM push_preferences")
        jdbc.update("DELETE FROM bundle_edit_leases")
        jdbc.update("DELETE FROM bundle_comments")
        jdbc.update("DELETE FROM bundle_participants")
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundle_requests")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        assetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        assetIds.clear()
    }

    @Test
    fun `joining records inbox and each current device job once in the same transaction`() {
        val owner = member()
        val guest = member()
        val bunch = create(owner)
        device(owner)
        device(owner)
        device(guest)
        val invitation = issue(owner, bunch.id)
        val key = UUID.randomUUID()

        join(guest, invitation, key)
        join(guest, invitation, key)

        assertThat(countMemberJoinedNotifications()).isEqualTo(1)
        assertThat(jobCount()).isEqualTo(2)
        assertThat(jdbc.queryForList("SELECT recipient_member_id FROM push_jobs", Long::class.java))
            .containsOnly(owner)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM push_jobs job JOIN notifications n ON job.notification_id = n.id " +
                "WHERE job.payload ->> 'eventId' = job.id::text AND n.type = 'MEMBER_JOINED'",
            Long::class.java
        )).isEqualTo(2)
    }

    @Test
    fun `job write failure rolls membership inbox and version back before a clean retry`() {
        val owner = member()
        val guest = member()
        val bunch = create(owner)
        device(owner)
        val invitation = issue(owner, bunch.id)
        val key = UUID.randomUUID()
        val version = bunchRepository.findById(bunch.id).orElseThrow().membershipVersion
        rejectJobWrites()

        assertThatThrownBy { join(guest, invitation, key) }.isInstanceOf(RuntimeException::class.java)

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_memberships WHERE bunch_id = ? AND member_id = ?",
            Long::class.java,
            bunch.id,
            guest
        )).isZero()
        assertThat(countMemberJoinedNotifications()).isZero()
        assertThat(jobCount()).isZero()
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().membershipVersion).isEqualTo(version)

        allowJobWrites()
        join(guest, invitation, key)
        assertThat(countMemberJoinedNotifications()).isEqualTo(1)
        assertThat(jobCount()).isEqualTo(1)
    }

    @Test
    fun `no device at the event is never backfilled by a successful request replay`() {
        val owner = member()
        val guest = member()
        val bunch = create(owner)
        val invitation = issue(owner, bunch.id)
        val key = UUID.randomUUID()
        join(guest, invitation, key)
        assertThat(jobCount()).isZero()

        device(owner)
        join(guest, invitation, key)

        assertThat(countMemberJoinedNotifications()).isEqualTo(1)
        assertThat(jobCount()).isZero()
    }

    @Test
    fun `disabled push retains the actual inbox change without a transmission job`() {
        val owner = member()
        val guest = member()
        val bunch = create(owner)
        device(owner)
        jdbc.update(
            "INSERT INTO push_preferences(member_id, enabled, types, updated_at) " +
                "VALUES (?, false, '{}'::jsonb, CURRENT_TIMESTAMP)",
            owner
        )

        join(guest, issue(owner, bunch.id))

        assertThat(countMemberJoinedNotifications()).isEqualTo(1)
        assertThat(jobCount()).isZero()
    }

    @Test
    fun `activity merge updates only existing jobs and reading does not cancel the scheduled push`() {
        val owner = member()
        val guest = member()
        val bunchId = create(owner).id
        join(guest, issue(owner, bunchId))
        device(guest)
        val draft = bundleService.create(
            BundleActor(owner, "atomic-owner"),
            bunchId,
            UUID.randomUUID(),
            CreateBundleRequest(listOf(photo(bunchId, guest)))
        )
        val firstKey = UUID.randomUUID()
        val firstChange = BundlePhotoChangesRequest(
            addPhotoIds = listOf(photo(bunchId, owner)),
            mode = BundleStatus.DRAFT
        )
        bundleService.changePhotos(BundleActor(owner, "atomic-owner"), bunchId, draft.id, firstKey, firstChange)
        val activity = notifications.findAll().single { it.type == NotificationType.DRAFT_ACTIVITY }
        val firstJob = jdbc.queryForMap("SELECT * FROM push_jobs WHERE notification_id = ?", activity.id)
        val originalTitle = jdbc.queryForObject(
            "SELECT payload -> 'aps' -> 'alert' ->> 'title' FROM push_jobs WHERE notification_id = ?",
            String::class.java,
            activity.id
        )
        inbox.read(guest, bunchId, activity.id)
        device(guest)
        jdbc.update("UPDATE bunches SET name = ? WHERE id = ?", uniqueName(), bunchId)

        bundleService.changePhotos(
            BundleActor(owner, "atomic-owner"),
            bunchId,
            draft.id,
            UUID.randomUUID(),
            BundlePhotoChangesRequest(
                addPhotoIds = listOf(photo(bunchId, owner)),
                mode = BundleStatus.DRAFT
            )
        )
        bundleService.changePhotos(BundleActor(owner, "atomic-owner"), bunchId, draft.id, firstKey, firstChange)

        val merged = notifications.findById(activity.id).orElseThrow()
        val jobs = jdbc.queryForList("SELECT * FROM push_jobs WHERE notification_id = ?", activity.id)
        assertThat(merged.activityData!!.photoCount).isEqualTo(2)
        assertThat(merged.readAt).isNull()
        assertThat(jobs).hasSize(1)
        assertThat(jobs.single()["id"]).isEqualTo(firstJob["id"])
        assertThat(jobs.single()["status"]).isEqualTo("PENDING")
        assertThat(jobs.single()["available_at"]).isEqualTo(firstJob["available_at"])
        assertThat(jobs.single()["payload"].toString()).contains("사진 2장")
        assertThat(jdbc.queryForObject(
            "SELECT payload -> 'aps' -> 'alert' ->> 'title' FROM push_jobs WHERE notification_id = ?",
            String::class.java,
            activity.id
        )).isEqualTo(originalTitle)
    }

    private fun device(memberId: Long) {
        val installation = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO push_devices(installation_id,member_id,session_id,environment,topic,
                token_ciphertext,token_hash,authorization_status,registered_at)
                VALUES (?,?,?,'SANDBOX','test.bunch',?,?, 'AUTHORIZED',CURRENT_TIMESTAMP)""",
            installation,
            memberId,
            UUID.randomUUID(),
            byteArrayOf(1, 2, 3),
            installation.toString().replace("-", "").repeat(2)
        )
    }

    private fun photo(bunchId: Long, memberId: Long): Long {
        val key = UUID.randomUUID().toString()
        val asset = assets.saveAndFlush(ImageAsset(publicId = "atomic/$key", url = "https://example.test/$key"))
        assetIds += asset.id
        val photo = photos.saveAndFlush(
            Photo(
                bunch = bunchRepository.findById(bunchId).orElseThrow(),
                imageAsset = asset,
                fileHash = key.replace("-", "").repeat(2),
                processingStatus = ProcessingStatus.READY
            )
        )
        uploaders.saveAndFlush(
            PhotoUploader(photo = photo, member = members.findById(memberId).orElseThrow(), uploadBatchId = UUID.randomUUID())
        )
        return photo.id
    }

    private fun jobCount(): Long = count("push_jobs")

    private fun rejectJobWrites() {
        jdbc.execute("""CREATE FUNCTION atomic_push_reject_write() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''injected atomic push write failure''; END'""")
        jdbc.execute("CREATE TRIGGER atomic_push_reject BEFORE INSERT ON push_jobs " +
            "FOR EACH ROW EXECUTE FUNCTION atomic_push_reject_write()")
    }

    private fun allowJobWrites() {
        jdbc.execute("DROP TRIGGER IF EXISTS atomic_push_reject ON push_jobs")
        jdbc.execute("DROP FUNCTION IF EXISTS atomic_push_reject_write()")
    }
}
