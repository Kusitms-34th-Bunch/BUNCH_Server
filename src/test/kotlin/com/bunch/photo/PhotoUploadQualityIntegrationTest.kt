package com.bunch.photo

import com.bunch.global.image.ImageQualityGrade
import com.bunch.global.image.PhotoImage
import com.bunch.photo.domain.PhotoRecoFeatureRepository
import com.bunch.photo.domain.PhotoSourceType
import com.bunch.photo.service.PhotoUploadKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** The quality of a photo is calculated when it is uploaded and kept with the other features (spec 4-4, 4-5). */
@SpringBootTest
@ActiveProfiles("test")
class PhotoUploadQualityIntegrationTest : PhotoUploadTestSupport() {
    @Autowired
    private lateinit var recoFeatures: PhotoRecoFeatureRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `an uploaded photo keeps the quality of its image`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val sharp = uploads.upload(command(bunchId, ownerId), picture(1600, 1200, dark = 60, light = 200))
        val flat = uploads.upload(command(bunchId, ownerId), picture(1600, 1200, dark = 128, light = 128))

        assertThat(qualityOf(sharp.photoId)).isEqualTo("GOOD")
        assertThat(qualityOf(flat.photoId)).isEqualTo("LOW")
    }

    @Test
    fun `the size that counts is the one of the photo that was uploaded`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val small = uploads.upload(command(bunchId, ownerId), picture(400, 300, dark = 60, light = 200))

        assertThat(qualityOf(small.photoId)).isEqualTo(ImageQualityGrade.LOW.name)
    }

    @Test
    fun `a photo whose image cannot be read is uploaded without a quality`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val result = uploads.upload(command(bunchId, ownerId), image("not a picture"))

        assertThat(result.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(qualityOf(result.photoId)).isNull()
        assertThat(rowCount(result.photoId)).isEqualTo(1)
    }

    @Test
    fun `the quality and the original type live in the one row of the photo`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val result = uploads.upload(
            command(bunchId, ownerId, sourceType = PhotoSourceType.CAMERA),
            picture(1600, 1200, dark = 60, light = 200)
        )

        assertThat(rowCount(result.photoId)).isEqualTo(1)
        assertThat(qualityOf(result.photoId)).isEqualTo("GOOD")
        assertThat(sourceOf(result.photoId)).isEqualTo("CAMERA")
    }

    @Test
    fun `the same photo uploaded by another member keeps the quality it has`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val second = joined(ownerId, bunchId)
        val photo = picture(1600, 1200, dark = 60, light = 200)
        val first = uploads.upload(command(bunchId, ownerId), photo)
        jdbc.update("UPDATE photo_reco_feature SET quality = 'NORMAL' WHERE photo_id = ?", first.photoId)

        val again = uploads.upload(command(bunchId, second), photo)

        assertThat(again.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(qualityOf(first.photoId)).isEqualTo("NORMAL")
        assertThat(rowCount(first.photoId)).isEqualTo(1)
    }

    @Test
    fun `a known quality is never replaced`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val result = uploads.upload(command(bunchId, ownerId), picture(1600, 1200, dark = 60, light = 200))

        val changed = TransactionTemplate(transactionManager).execute {
            recoFeatures.fillQuality(result.photoId, "LOW")
        }

        assertThat(changed).isZero()
        assertThat(qualityOf(result.photoId)).isEqualTo("GOOD")
    }

    @Test
    fun `the quality is deleted with the photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val result = uploads.upload(command(bunchId, ownerId), picture(1600, 1200, dark = 60, light = 200))

        jdbc.update("DELETE FROM photo_uploaders WHERE photo_id = ?", result.photoId)
        jdbc.update("DELETE FROM photos WHERE id = ?", result.photoId)

        assertThat(rowCount(result.photoId)).isZero()
    }

    private fun picture(width: Int, height: Int, dark: Int, light: Int): PhotoImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        for (y in 0 until height step CELL) {
            for (x in 0 until width step CELL) {
                val grey = if ((x / CELL + y / CELL) % 2 == 0) dark else light
                graphics.color = Color(grey, grey, grey)
                graphics.fillRect(x, y, CELL, CELL)
            }
        }
        graphics.dispose()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        return PhotoImage(bytes, width, height)
    }

    private fun qualityOf(photoId: Long): String? = jdbc.queryForList(
        "SELECT quality FROM photo_reco_feature WHERE photo_id = ?",
        String::class.java,
        photoId
    ).firstOrNull()

    private fun sourceOf(photoId: Long): String? = jdbc.queryForList(
        "SELECT source FROM photo_reco_feature WHERE photo_id = ?",
        String::class.java,
        photoId
    ).firstOrNull()

    private fun rowCount(photoId: Long): Int = jdbc.queryForObject(
        "SELECT count(*) FROM photo_reco_feature WHERE photo_id = ?",
        Int::class.java,
        photoId
    )!!

    private companion object {
        const val CELL = 8
    }
}
