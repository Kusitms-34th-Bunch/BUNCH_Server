package com.bunch.photo

import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ImageFingerprint
import com.bunch.global.image.PhotoImage
import com.bunch.photo.service.PhotoUploadKind
import com.bunch.photo.service.PhotoUploadLimits
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The same photo is the one with the same original file hash (photo box 3.2.2 rule 5): the app computes it before it
 * shrinks the photo, so one original gives one hash although the shrunk file differs from device to device.
 */
@SpringBootTest
@ActiveProfiles("test")
class PhotoUploadOriginalHashIntegrationTest : PhotoUploadTestSupport() {
    private val originalA = "a".repeat(64)
    private val originalB = "b".repeat(64)

    @Test
    fun `the same original hash joins a copy encoded differently and uploads the file only once`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val firstCopy = brightToDark(quality = HIGH_QUALITY)
        val secondCopy = brightToDark(quality = LOW_QUALITY)
        assertThat(hashOf(firstCopy)).isNotEqualTo(hashOf(secondCopy))
        assertThat(distance(firstCopy, secondCopy)).isLessThanOrEqualTo(MAX_DIFFERENCE)

        val first = uploads.upload(command(bunchId, ownerId, originalFileHash = originalA), firstCopy)
        val merged = uploads.upload(command(bunchId, otherId, originalFileHash = originalA), secondCopy)

        assertThat(first.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(merged.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(merged.photoId).isEqualTo(first.photoId)
        assertThat(photoCount(bunchId)).isEqualTo(1)
        assertThat(uploaderCount(first.photoId)).isEqualTo(2)
        assertThat(originalHashOf(first.photoId)).isEqualTo(originalA)
        verify(storage, times(1)).upload(anyImage())
    }

    @Test
    fun `a copy of an original I already uploaded is excluded and returns the existing photo`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val first = uploads.upload(command(bunchId, ownerId, originalFileHash = originalA), brightToDark(HIGH_QUALITY))
        val again = uploads.upload(command(bunchId, ownerId, originalFileHash = originalA), brightToDark(LOW_QUALITY))

        assertThat(again.kind).isEqualTo(PhotoUploadKind.ALREADY_UPLOADED)
        assertThat(again.photoId).isEqualTo(first.photoId)
        assertThat(photoCount(bunchId)).isEqualTo(1)
        assertThat(uploaderCount(first.photoId)).isEqualTo(1)
    }

    @Test
    fun `without an original hash a copy encoded differently stays a separate photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)

        val first = uploads.upload(command(bunchId, ownerId), brightToDark(HIGH_QUALITY))
        val second = uploads.upload(command(bunchId, otherId), brightToDark(LOW_QUALITY))

        assertThat(second.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(second.photoId).isNotEqualTo(first.photoId)
        assertThat(originalHashOf(first.photoId)).isNull()
        assertThat(photoCount(bunchId)).isEqualTo(2)
    }

    @Test
    fun `another original hash keeps a separate photo even when the pictures look alike`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)

        val first = uploads.upload(command(bunchId, ownerId, originalFileHash = originalA), brightToDark(HIGH_QUALITY))
        val second = uploads.upload(command(bunchId, otherId, originalFileHash = originalB), brightToDark(LOW_QUALITY))

        assertThat(second.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(second.photoId).isNotEqualTo(first.photoId)
        assertThat(originalHashOf(second.photoId)).isEqualTo(originalB)
        assertThat(photoCount(bunchId)).isEqualTo(2)
    }

    @Test
    fun `the same original hash on a picture that looks different is stored as a new photo without the hash`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val first = brightToDark(HIGH_QUALITY)
        val different = darkToBright(HIGH_QUALITY)
        assertThat(distance(first, different)).isGreaterThan(MAX_DIFFERENCE)

        val stored = uploads.upload(command(bunchId, ownerId, originalFileHash = originalA), first)
        val second = uploads.upload(command(bunchId, otherId, originalFileHash = originalA), different)

        assertThat(second.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(second.photoId).isNotEqualTo(stored.photoId)
        assertThat(originalHashOf(stored.photoId)).isEqualTo(originalA)
        assertThat(originalHashOf(second.photoId)).isNull()
        assertThat(photoCount(bunchId)).isEqualTo(2)
        verify(storage, times(2)).upload(anyImage())
    }

    @Test
    fun `a photo without a stored fingerprint is trusted when the original hash matches`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val existingId = insertPhoto(bunchId, hashOf(image("existing")), uploaderId = ownerId)
        jdbc.update("UPDATE photos SET original_file_hash = ? WHERE id = ?", originalA, existingId)

        val merged = uploads.upload(command(bunchId, otherId, originalFileHash = originalA), brightToDark(HIGH_QUALITY))

        assertThat(merged.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(merged.photoId).isEqualTo(existingId)
        assertThat(photoCount(bunchId)).isEqualTo(1)
    }

    @Test
    fun `the same file with another original hash is merged by the file and keeps the first original hash`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val file = brightToDark(HIGH_QUALITY)

        val first = uploads.upload(command(bunchId, ownerId, originalFileHash = originalA), file)
        val merged = uploads.upload(command(bunchId, otherId, originalFileHash = originalB), file)

        assertThat(merged.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(merged.photoId).isEqualTo(first.photoId)
        assertThat(originalHashOf(first.photoId)).isEqualTo(originalA)
        assertThat(photoCount(bunchId)).isEqualTo(1)
    }

    @Test
    fun `the same original hash in another bunch is a separate photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherBunchId = create(ownerId).id
        val file = brightToDark(HIGH_QUALITY)

        val first = uploads.upload(command(bunchId, ownerId, originalFileHash = originalA), file)
        val second = uploads.upload(command(otherBunchId, ownerId, originalFileHash = originalA), file)

        assertThat(second.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(second.photoId).isNotEqualTo(first.photoId)
    }

    @Test
    fun `an original hash that is not a lowercase SHA-256 is rejected before anything is uploaded`() {
        val (ownerId, bunchId) = bunchWithOwner()

        listOf("A".repeat(64), "a".repeat(63), "a".repeat(65), "g".repeat(64), " ".repeat(64), "").forEach { invalid ->
            assertError(ErrorCode.INVALID_INPUT) {
                uploads.upload(command(bunchId, ownerId, originalFileHash = invalid), brightToDark(HIGH_QUALITY))
            }
        }

        assertThat(photoCount(bunchId)).isZero()
        verify(storage, never()).upload(anyImage())
    }

    @Test
    fun `the database keeps one photo per original hash in a bunch and only lowercase SHA-256 values`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val first = insertPhoto(bunchId, hashOf(image("one")), uploaderId = ownerId)
        val second = insertPhoto(bunchId, hashOf(image("two")), uploaderId = ownerId)

        jdbc.update("UPDATE photos SET original_file_hash = ? WHERE id = ?", originalA, first)

        assertThatThrownBy { jdbc.update("UPDATE photos SET original_file_hash = ? WHERE id = ?", originalA, second) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("uk_photo_bunch_original_file_hash")
        assertThatThrownBy {
            jdbc.update("UPDATE photos SET original_file_hash = ? WHERE id = ?", "A".repeat(64), second)
        }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_photo_original_file_hash_sha256")
        // Photos without the value are not compared with each other.
        assertThat(originalHashOf(second)).isNull()
    }

    // Every cell is brighter than the cell on its right, so all 64 bits of the fingerprint are set. Another encoding
    // of the same picture keeps them.
    private fun brightToDark(quality: Float): PhotoImage = encode(gradient(reversed = false), quality)

    // The opposite picture: no bit of its fingerprint is set, so it differs from the one above in all 64.
    private fun darkToBright(quality: Float): PhotoImage = encode(gradient(reversed = true), quality)

    private fun gradient(reversed: Boolean): BufferedImage {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        for (x in 0 until WIDTH) {
            val level = 255 - x * 255 / (WIDTH - 1)
            val gray = if (reversed) 255 - level else level
            graphics.color = Color(gray, gray, gray)
            graphics.fillRect(x, 0, 1, HEIGHT)
        }
        graphics.dispose()
        return image
    }

    private fun encode(image: BufferedImage, quality: Float): PhotoImage {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val parameters = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val bytes = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bytes).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), parameters)
        }
        writer.dispose()
        return PhotoImage(bytes.toByteArray(), WIDTH, HEIGHT)
    }

    private fun distance(first: PhotoImage, second: PhotoImage): Int =
        java.lang.Long.bitCount(ImageFingerprint.of(first.bytes)!! xor ImageFingerprint.of(second.bytes)!!)

    private fun originalHashOf(photoId: Long): String? =
        jdbc.queryForObject("SELECT original_file_hash FROM photos WHERE id = ?", String::class.java, photoId)

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240

        // Two encodings of one picture: the same image, a different file.
        const val HIGH_QUALITY = 0.95f
        const val LOW_QUALITY = 0.30f
        const val MAX_DIFFERENCE = PhotoUploadLimits.MAX_ORIGINAL_HASH_FINGERPRINT_DIFFERENCE
    }
}
