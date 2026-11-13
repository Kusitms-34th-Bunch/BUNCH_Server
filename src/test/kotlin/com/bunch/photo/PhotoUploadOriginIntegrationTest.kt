package com.bunch.photo

import com.bunch.photo.domain.PhotoSourceType.CAMERA
import com.bunch.photo.domain.PhotoSourceType.SAVED
import com.bunch.photo.domain.PhotoSourceType.SCREENSHOT
import com.bunch.photo.service.PhotoUploadKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalTime

/**
 * The UTC offset and the original type the app sends with a photo (recommended bundle specification 3-6, 4-4, 4-5,
 * 5-2). Both are optional, a known value is never replaced, and a missing one is filled in by a later upload of the
 * same file.
 */
@SpringBootTest
@ActiveProfiles("test")
class PhotoUploadOriginIntegrationTest : PhotoUploadTestSupport() {
    @Test
    fun `a new photo keeps the offset and the original type it was uploaded with`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val result = uploads.upload(
            command(
                bunchId, ownerId,
                capturedDate = LocalDate.of(2026, 10, 19),
                capturedTime = LocalTime.of(21, 10),
                capturedUtcOffset = "+09:00",
                sourceType = CAMERA
            ),
            image("with-origin")
        )

        assertThat(result.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(offsetOf(result.photoId)).isEqualTo("+09:00")
        assertThat(sourceOf(result.photoId)).isEqualTo("CAMERA")
    }

    @Test
    fun `a photo uploaded without them has no offset, and its row has no original type`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val result = uploads.upload(command(bunchId, ownerId), image("without-origin"))

        assertThat(result.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(offsetOf(result.photoId)).isNull()
        // Every new photo has its row, so the features that come later are filled into it.
        assertThat(featureRows(result.photoId)).isEqualTo(1)
        assertThat(sourceOf(result.photoId)).isNull()
    }

    @Test
    fun `each original type is kept as it was named`() {
        val (ownerId, bunchId) = bunchWithOwner()

        listOf(CAMERA, SCREENSHOT, SAVED).forEach { type ->
            val result = uploads.upload(command(bunchId, ownerId, sourceType = type), image("type-${type.name}"))
            assertThat(sourceOf(result.photoId)).isEqualTo(type.name)
        }
    }

    @Test
    fun `a merged upload fills what the first upload did not say`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("fill-by-merge")
        val first = uploads.upload(command(bunchId, ownerId), photo)

        val merged = uploads.upload(
            command(bunchId, otherId, capturedUtcOffset = "-05:00", sourceType = SAVED),
            photo
        )

        assertThat(merged.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(offsetOf(first.photoId)).isEqualTo("-05:00")
        assertThat(sourceOf(first.photoId)).isEqualTo("SAVED")
    }

    @Test
    fun `a file I uploaded again fills what my first upload did not say`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photo = image("fill-by-retry")
        val first = uploads.upload(command(bunchId, ownerId), photo)

        val again = uploads.upload(
            command(bunchId, ownerId, capturedUtcOffset = "+09:00", sourceType = CAMERA),
            photo
        )

        assertThat(again.kind).isEqualTo(PhotoUploadKind.ALREADY_UPLOADED)
        assertThat(offsetOf(first.photoId)).isEqualTo("+09:00")
        assertThat(sourceOf(first.photoId)).isEqualTo("CAMERA")
    }

    @Test
    fun `a value that is already known is never replaced`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("keep-known")
        val first = uploads.upload(
            command(bunchId, ownerId, capturedUtcOffset = "+09:00", sourceType = CAMERA),
            photo
        )

        uploads.upload(command(bunchId, otherId, capturedUtcOffset = "-05:00", sourceType = SAVED), photo)
        uploads.upload(command(bunchId, ownerId, capturedUtcOffset = "+01:00", sourceType = SCREENSHOT), photo)

        assertThat(offsetOf(first.photoId)).isEqualTo("+09:00")
        assertThat(sourceOf(first.photoId)).isEqualTo("CAMERA")
    }

    @Test
    fun `a merged upload that says nothing changes nothing`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("say-nothing")
        val first = uploads.upload(
            command(bunchId, ownerId, capturedUtcOffset = "+09:00", sourceType = CAMERA),
            photo
        )

        uploads.upload(command(bunchId, otherId), photo)

        assertThat(offsetOf(first.photoId)).isEqualTo("+09:00")
        assertThat(sourceOf(first.photoId)).isEqualTo("CAMERA")
    }

    @Test
    fun `a photo that was stored before the table existed gets its row from a later upload`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("old-photo")
        val first = uploads.upload(command(bunchId, ownerId), photo)
        jdbc.update("DELETE FROM photo_reco_feature WHERE photo_id = ?", first.photoId)

        uploads.upload(command(bunchId, otherId, sourceType = CAMERA), photo)

        assertThat(featureRows(first.photoId)).isEqualTo(1)
        assertThat(sourceOf(first.photoId)).isEqualTo("CAMERA")
    }

    @Test
    fun `an upload that says nothing does not make a row for an old photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("old-photo-silent")
        val first = uploads.upload(command(bunchId, ownerId), photo)
        jdbc.update("DELETE FROM photo_reco_feature WHERE photo_id = ?", first.photoId)

        uploads.upload(command(bunchId, otherId), photo)

        assertThat(featureRows(first.photoId)).isZero()
    }

    @Test
    fun `deleting the photo deletes its row`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val result = uploads.upload(command(bunchId, ownerId, sourceType = CAMERA), image("delete-me"))

        jdbc.update("DELETE FROM photo_uploaders WHERE photo_id = ?", result.photoId)
        jdbc.update("DELETE FROM photos WHERE id = ?", result.photoId)

        assertThat(featureRows(result.photoId)).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["+09:00", "-05:00", "+00:00", "-00:00", "+05:45", "+14:00", "-12:00"])
    fun `the database keeps an offset of the right shape`(offset: String) {
        val (_, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, hashOf(image("offset-$offset")))

        jdbc.update("UPDATE photos SET captured_utc_offset = ? WHERE id = ?", offset, photoId)

        assertThat(offsetOf(photoId)).isEqualTo(offset)
    }

    @ParameterizedTest
    @ValueSource(strings = ["+9:00", "+0900", "09:00", "+09:60", "+09:5", "UTC", "", " +09:00", "+٠٩:٠٠"])
    fun `the database refuses an offset of the wrong shape`(offset: String) {
        val (_, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, hashOf(image("bad-offset-$offset")))

        assertThatThrownBy {
            jdbc.update("UPDATE photos SET captured_utc_offset = ? WHERE id = ?", offset, photoId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `the database refuses an original type the specification does not know`() {
        val (_, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, hashOf(image("bad-source")))

        listOf("SAVED_IMAGE", "UNKNOWN", "camera", "").forEach { source ->
            assertThatThrownBy {
                jdbc.update("INSERT INTO photo_reco_feature (photo_id, source) VALUES (?, ?)", photoId, source)
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }
    }

    private fun offsetOf(photoId: Long): String? = jdbc.queryForObject(
        "SELECT captured_utc_offset FROM photos WHERE id = ?",
        String::class.java,
        photoId
    )

    private fun sourceOf(photoId: Long): String? = jdbc.queryForList(
        "SELECT source FROM photo_reco_feature WHERE photo_id = ?",
        String::class.java,
        photoId
    ).singleOrNull()

    private fun featureRows(photoId: Long): Int = jdbc.queryForObject(
        "SELECT count(*) FROM photo_reco_feature WHERE photo_id = ?",
        Int::class.java,
        photoId
    )!!
}
