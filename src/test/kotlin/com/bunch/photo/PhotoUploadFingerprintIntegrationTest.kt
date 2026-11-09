package com.bunch.photo

import com.bunch.global.image.ImageFingerprint
import com.bunch.global.image.PhotoImage
import com.bunch.photo.service.PhotoUploadKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@SpringBootTest
@ActiveProfiles("test")
class PhotoUploadFingerprintIntegrationTest : PhotoUploadTestSupport() {
    @Test
    fun `an uploaded photo keeps the fingerprint of its image`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photo = picture()

        val result = uploads.upload(command(bunchId, ownerId), photo)

        assertThat(result.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(fingerprintOf(result.photoId)).isEqualTo(ImageFingerprint.of(photo.bytes))
        assertThat(fingerprintOf(result.photoId)).isNotNull()
    }

    @Test
    fun `a photo whose image cannot be read is uploaded without a fingerprint`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val result = uploads.upload(command(bunchId, ownerId), image("not a picture"))

        assertThat(result.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(fingerprintCount(result.photoId)).isZero()
    }

    @Test
    fun `the same file uploaded by another member keeps the one fingerprint`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val second = joined(ownerId, bunchId)
        val photo = picture()

        val first = uploads.upload(command(bunchId, ownerId), photo)
        val again = uploads.upload(command(bunchId, second), photo)

        assertThat(again.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(again.photoId).isEqualTo(first.photoId)
        assertThat(fingerprintCount(first.photoId)).isEqualTo(1)
    }

    @Test
    fun `the fingerprint is deleted with the photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val result = uploads.upload(command(bunchId, ownerId), picture())

        jdbc.update("DELETE FROM photo_uploaders WHERE photo_id = ?", result.photoId)
        jdbc.update("DELETE FROM photos WHERE id = ?", result.photoId)

        assertThat(fingerprintCount(result.photoId)).isZero()
    }

    private fun picture(): PhotoImage {
        val image = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(40, 90, 160)
        graphics.fillRect(0, 0, 320, 240)
        graphics.color = Color(240, 220, 120)
        graphics.fillRect(30, 20, 150, 90)
        graphics.dispose()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        return PhotoImage(bytes, 320, 240)
    }

    private fun fingerprintOf(photoId: Long): Long? =
        jdbc.queryForList(
            "SELECT fingerprint FROM photo_image_fingerprints WHERE photo_id = ?",
            Long::class.java,
            photoId
        ).firstOrNull()

    private fun fingerprintCount(photoId: Long): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM photo_image_fingerprints WHERE photo_id = ?",
            Int::class.java,
            photoId
        )!!
}
