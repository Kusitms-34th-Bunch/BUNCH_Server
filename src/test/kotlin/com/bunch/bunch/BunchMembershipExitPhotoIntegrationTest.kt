package com.bunch.bunch

import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundlePhoto
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.global.image.PhotoImageStorage
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.DateSource
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.service.PhotoChangedEvent
import com.bunch.bunch.dto.BunchRemovalRequest
import com.bunch.bunch.service.BunchMembershipExitService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class BunchMembershipExitPhotoIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var events: ApplicationEvents

    @Autowired
    private lateinit var exits: BunchMembershipExitService

    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Autowired
    private lateinit var bunchesRepository: BunchRepository

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var bundlePhotos: BundlePhotoRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var uploaders: PhotoUploaderRepository

    @MockitoBean
    private lateinit var storage: PhotoImageStorage

    @AfterEach
    fun cleanPhotoAndBundleRowsBeforeBunchSupport() {
        jdbc.update("DELETE FROM bundle_edit_leases")
        jdbc.update("DELETE FROM bundle_comments")
        jdbc.update("DELETE FROM bundle_participants")
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundle_requests")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets")
    }

    @Test
    fun `exit removes final uploader but preserves shared and published bundle image data`() {
        val owner = member()
        val removed = member()
        val remaining = member()
        val bunch = create(owner)
        join(removed, issue(owner, bunch.id))
        join(remaining, issue(owner, bunch.id))
        val shared = photo(bunch.id, listOf(removed, remaining))
        val publishedSource = photo(bunch.id, listOf(removed))
        val publishedAsset = publishedSource.imageAsset.id
        val publishedBundle = bundles.saveAndFlush(
            Bundle(
                bunchId = bunch.id,
                createdBy = owner,
                status = BundleStatus.PUBLISHED,
                title = "published"
            )
        )
        val publishedPhoto = bundlePhotos.saveAndFlush(
            BundlePhoto(
                bundleId = publishedBundle.id,
                photoId = publishedSource.id,
                imageAsset = publishedSource.imageAsset,
                position = 0,
                addedBy = removed
            )
        )
        val historicalUpdatedAt = java.sql.Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"))
        jdbc.update(
            """UPDATE bundle_photos SET caption = 'keep this caption', caption_version = 2,
                caption_updated_by = ?, caption_updated_at = ? WHERE id = ?""",
            removed,
            historicalUpdatedAt,
            publishedPhoto.id
        )
        val ownerMembership = memberships.findByMemberIdAndBunchIdAndStatus(owner, bunch.id)!!
        val removedMembership = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunch.id)!!

        exits.remove(
            owner,
            bunch.id,
            removedMembership.id,
            BunchRemovalRequest(ownerMembership.id, bunchVersion(bunch.id))
        )

        assertThat(photos.existsById(shared.id)).isTrue()
        assertThat(uploaders.findAllByPhotoIdInOrderByPhotoIdAndIdAsc(listOf(shared.id)))
            .extracting<Long> { it.member.id }
            .containsExactly(remaining)
        assertThat(photos.existsById(publishedSource.id)).isFalse()
        val retained = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(publishedBundle.id).single()
        assertThat(retained.photoId).isNull()
        val legacy = jdbc.queryForMap(
            "SELECT caption, caption_version, caption_updated_by, caption_updated_at FROM bundle_photos WHERE id = ?",
            publishedPhoto.id
        )
        assertThat(legacy["caption"]).isEqualTo("keep this caption")
        assertThat((legacy["caption_version"] as Number).toLong()).isEqualTo(2L)
        assertThat((legacy["caption_updated_by"] as Number).toLong()).isEqualTo(removed)
        assertThat(legacy["caption_updated_at"]).isEqualTo(historicalUpdatedAt)
        assertThat(retained.imageAsset.id).isEqualTo(publishedAsset)
        assertThat(assets.existsById(publishedAsset)).isTrue()
    }

    @Test
    fun `photo cleanup failure rolls back membership and invitation rotation`() {
        val owner = member()
        val removed = member()
        val bunch = create(owner)
        val invitation = issue(owner, bunch.id)
        join(removed, invitation)
        val source = photo(bunch.id, listOf(removed))
        val ownerMembership = memberships.findByMemberIdAndBunchIdAndStatus(owner, bunch.id)!!
        val removedMembership = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunch.id)!!
        val currentVersion = bunchVersion(bunch.id)
        rejectWrite("bunch_test_reject_photo_delete", "photos", "DELETE")

        try {
            assertThatThrownBy {
                exits.remove(
                    owner,
                    bunch.id,
                    removedMembership.id,
                    BunchRemovalRequest(ownerMembership.id, currentVersion)
                )
            }.isInstanceOf(DataAccessException::class.java)
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_photo_delete ON photos")
            jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        }

        assertThat(bunchVersion(bunch.id)).isEqualTo(currentVersion)
        assertThat(memberships.findById(removedMembership.id).orElseThrow().status)
            .isEqualTo(com.bunch.bunch.domain.MembershipStatus.ACTIVE)
        assertThat(photos.existsById(source.id)).isTrue()
        assertThat(uploaders.existsByPhotoIdAndMemberId(source.id, removed)).isTrue()
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_access_notices WHERE recipient_member_id = ? AND bunch_id = ?",
            Long::class.java,
            removed,
            bunch.id
        )).isEqualTo(0L)
        assertThat(issue(owner, bunch.id).code).isEqualTo(invitation.code)
    }

    @Test
    fun `exit announces each dated photo change once and skips undated photos`() {
        val owner = member()
        val removed = member()
        val remaining = member()
        val bunch = create(owner)
        join(removed, issue(owner, bunch.id))
        join(remaining, issue(owner, bunch.id))
        val sharedDate = LocalDate.of(2026, 10, 19)
        val deletedDate = sharedDate.plusDays(1)
        photo(bunch.id, listOf(removed, remaining), sharedDate)
        photo(bunch.id, listOf(removed), deletedDate)
        photo(bunch.id, listOf(removed, remaining))
        photo(bunch.id, listOf(removed))
        val ownerMembership = memberships.findByMemberIdAndBunchIdAndStatus(owner, bunch.id)!!
        val removedMembership = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunch.id)!!

        exits.remove(
            owner,
            bunch.id,
            removedMembership.id,
            BunchRemovalRequest(ownerMembership.id, bunchVersion(bunch.id))
        )

        assertThat(events.stream(PhotoChangedEvent::class.java).toList()).containsExactly(
            PhotoChangedEvent(bunch.id, sharedDate, photoAdded = false),
            PhotoChangedEvent(bunch.id, deletedDate, photoAdded = false)
        )
    }

    private fun photo(
        bunchId: Long,
        uploaderIds: List<Long>,
        capturedDate: LocalDate? = null
    ): Photo {
        val asset = assets.saveAndFlush(
            ImageAsset(
                publicId = "exit-test/${UUID.randomUUID()}",
                url = "https://images.example/exit/${UUID.randomUUID()}.jpg"
            )
        )
        val photo = photos.saveAndFlush(
            Photo(
                bunch = bunchesRepository.findById(bunchId).orElseThrow(),
                imageAsset = asset,
                fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2),
                processingStatus = com.bunch.photo.domain.ProcessingStatus.READY,
                capturedDate = capturedDate,
                dateSource = if (capturedDate == null) DateSource.UNKNOWN else DateSource.USER,
                createdAt = Instant.parse("2026-01-01T00:00:00Z")
            )
        )
        uploaderIds.forEach { memberId ->
            uploaders.saveAndFlush(
                PhotoUploader(
                    photo = photo,
                    member = members.findById(memberId).orElseThrow(),
                    uploadBatchId = UUID.randomUUID(),
                    uploadedAt = photo.createdAt
                )
            )
        }
        return photo
    }

    private fun bunchVersion(bunchId: Long): Long = jdbc.queryForObject(
        "SELECT membership_version FROM bunches WHERE id = ?",
        Long::class.java,
        bunchId
    )!!
}
