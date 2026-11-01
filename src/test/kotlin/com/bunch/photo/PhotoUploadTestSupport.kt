package com.bunch.photo

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.image.PhotoImage
import com.bunch.global.image.PhotoImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.photo.service.PhotoUploadCommand
import com.bunch.photo.service.PhotoUploadService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

abstract class PhotoUploadTestSupport : BunchIntegrationSupport() {
    @Autowired
    protected lateinit var uploads: PhotoUploadService

    @MockitoBean
    protected lateinit var storage: PhotoImageStorage

    @BeforeEach
    fun stubPhotoStorage() {
        `when`(storage.upload(anyImage())).thenAnswer { stored() }
    }

    @AfterEach
    fun cleanPhotoUploads() {
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'photos/test-%'")
    }

    // Mockito's any() returns null, which a non-null Kotlin parameter would reject.
    protected fun anyImage(): PhotoImage =
        ArgumentMatchers.any(PhotoImage::class.java) ?: PhotoImage(ByteArray(0), 1, 1)

    protected fun bunchWithOwner(): Pair<Long, Long> {
        val ownerId = member()
        return ownerId to create(ownerId).id
    }

    protected fun joined(ownerId: Long, bunchId: Long): Long {
        val memberId = member()
        join(memberId, issue(ownerId, bunchId))
        return memberId
    }

    protected fun command(
        bunchId: Long,
        memberId: Long,
        batchId: UUID = UUID.randomUUID(),
        capturedDate: LocalDate? = null,
        capturedTime: LocalTime? = null
    ) = PhotoUploadCommand(bunchId, memberId, batchId, capturedDate, capturedTime)

    protected fun image(seed: String) = PhotoImage(seed.toByteArray(), 1200, 800)

    protected fun hashOf(image: PhotoImage): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image.bytes))

    protected fun stored(publicId: String = "photos/test-${UUID.randomUUID()}") =
        StoredImage(publicId, "https://res.cloudinary.com/test/image/upload/$publicId.jpg")

    protected fun seedAsset(): Long = jdbc.queryForObject(
        "INSERT INTO image_assets (public_id, url, created_at) VALUES (?, ?, now()) RETURNING id",
        Long::class.java,
        "photos/test-seed-${UUID.randomUUID()}",
        "https://res.cloudinary.com/test/image/upload/seed.jpg"
    )!!

    // Many photos at once without going through the service; they share one asset row.
    protected fun seedPhotos(
        bunchId: Long,
        count: Int,
        uploaderId: Long? = null,
        uploadedAt: Instant = Instant.now(),
        batchId: UUID? = null
    ) {
        val assetId = seedAsset()
        val tag = UUID.randomUUID().toString()
        val insertPhotos = """INSERT INTO photos
            (bunch_id, image_asset_id, file_hash, processing_status, date_source, created_at)
            SELECT ?, ?, encode(sha256(convert_to(CAST(? AS text) || g::text, 'UTF8')), 'hex'),
                'READY', 'UNKNOWN', now()
            FROM generate_series(1, ?) AS g"""
        if (uploaderId == null) {
            jdbc.update(insertPhotos, bunchId, assetId, tag, count)
            return
        }
        val batch = if (batchId == null) "gen_random_uuid()" else "CAST(? AS uuid)"
        val sql = """WITH inserted AS ($insertPhotos RETURNING id)
            INSERT INTO photo_uploaders (photo_id, member_id, upload_batch_id, uploaded_at)
            SELECT id, ?, $batch, ? FROM inserted"""
        val args = listOfNotNull<Any>(
            bunchId,
            assetId,
            tag,
            count,
            uploaderId,
            batchId?.toString(),
            OffsetDateTime.ofInstant(uploadedAt, ZoneOffset.UTC)
        )
        jdbc.update(sql, *args.toTypedArray())
    }

    protected fun insertPhoto(
        bunchId: Long,
        hash: String,
        uploaderId: Long? = null,
        batchId: UUID = UUID.randomUUID()
    ): Long {
        val id = jdbc.queryForObject(
            """INSERT INTO photos (bunch_id, image_asset_id, file_hash, processing_status, date_source, created_at)
                VALUES (?, ?, ?, 'READY', 'UNKNOWN', now()) RETURNING id""",
            Long::class.java,
            bunchId,
            seedAsset(),
            hash
        )!!
        if (uploaderId != null) {
            jdbc.update(
                """INSERT INTO photo_uploaders (photo_id, member_id, upload_batch_id, uploaded_at)
                    VALUES (?, ?, CAST(? AS uuid), now())""",
                id,
                uploaderId,
                batchId.toString()
            )
        }
        return id
    }

    protected fun photoCount(bunchId: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM photos WHERE bunch_id = ?", Long::class.java, bunchId)!!

    protected fun uploaderCount(photoId: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM photo_uploaders WHERE photo_id = ?", Long::class.java, photoId)!!

    protected fun memberUploadCount(memberId: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM photo_uploaders WHERE member_id = ?", Long::class.java, memberId)!!
}
