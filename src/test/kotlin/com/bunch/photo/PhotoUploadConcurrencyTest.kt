package com.bunch.photo

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.photo.service.PhotoUploadKind
import com.bunch.photo.service.PhotoUploadLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class PhotoUploadConcurrencyTest : PhotoUploadTestSupport() {
    @Test
    fun `two members uploading the same file at once store one photo with two uploaders`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val shared = image("same-moment")

        val results = concurrently(
            listOf(
                { uploads.upload(command(bunchId, ownerId), shared) },
                { uploads.upload(command(bunchId, otherId), shared) }
            )
        )

        assertThat(results.all { it.isSuccess }).isTrue()
        val outcomes = results.map { it.getOrThrow() }
        assertThat(outcomes.map { it.kind }).containsExactlyInAnyOrder(PhotoUploadKind.STORED, PhotoUploadKind.MERGED)
        assertThat(outcomes.map { it.photoId }.distinct()).hasSize(1)
        assertThat(photoCount(bunchId)).isEqualTo(1)
        assertThat(uploaderCount(outcomes.first().photoId)).isEqualTo(2)
    }

    @Test
    fun `the same member retrying the same file at once stores it only once`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photo = image("double-tap")

        val results = concurrently(
            listOf(
                { uploads.upload(command(bunchId, ownerId), photo) },
                { uploads.upload(command(bunchId, ownerId), photo) }
            )
        )

        assertThat(results.all { it.isSuccess }).isTrue()
        val outcomes = results.map { it.getOrThrow() }
        assertThat(outcomes.map { it.kind })
            .containsExactlyInAnyOrder(PhotoUploadKind.STORED, PhotoUploadKind.ALREADY_UPLOADED)
        assertThat(photoCount(bunchId)).isEqualTo(1)
        assertThat(uploaderCount(outcomes.first().photoId)).isEqualTo(1)
    }

    @Test
    fun `one member uploading to the same bunch at once cannot pass the daily limit`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val dailyLimit = PhotoUploadLimits.MAX_PHOTOS_PER_MEMBER_PER_BUNCH_PER_DAY
        seedPhotos(bunchId, dailyLimit - 1, ownerId)

        val results = concurrently(
            (1..4).map { index -> { uploads.upload(command(bunchId, ownerId), image("daily-$index")) } }
        )

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(errorCodes(results)).containsOnly(ErrorCode.PHOTO_DAILY_LIMIT_EXCEEDED)
        assertThat(memberUploadCount(ownerId)).isEqualTo(dailyLimit.toLong())
    }

    @Test
    fun `a full daily limit in one bunch does not block uploads to another bunch at the same time`() {
        val (ownerId, fullBunchId) = bunchWithOwner()
        val otherBunchId = create(ownerId).id
        seedPhotos(fullBunchId, PhotoUploadLimits.MAX_PHOTOS_PER_MEMBER_PER_BUNCH_PER_DAY, ownerId)

        val results = concurrently(
            listOf(
                { uploads.upload(command(fullBunchId, ownerId), image("full-bunch")) },
                { uploads.upload(command(otherBunchId, ownerId), image("other-bunch")) }
            )
        )

        assertThat(errorCodes(results)).containsExactly(ErrorCode.PHOTO_DAILY_LIMIT_EXCEEDED)
        assertThat(photoCount(otherBunchId)).isEqualTo(1)
    }

    @Test
    fun `members uploading at once cannot push a bunch past its photo limit`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val memberIds = listOf(ownerId) + (1..3).map { joined(ownerId, bunchId) }
        seedPhotos(bunchId, PhotoUploadLimits.MAX_PHOTOS_PER_BUNCH - 2)

        val results = concurrently(
            memberIds.mapIndexed { index, memberId ->
                { uploads.upload(command(bunchId, memberId), image("bunch-limit-$index")) }
            }
        )

        assertThat(results.count { it.isSuccess }).isEqualTo(2)
        assertThat(errorCodes(results)).containsOnly(ErrorCode.PHOTO_BUNCH_LIMIT_EXCEEDED)
        assertThat(photoCount(bunchId)).isEqualTo(PhotoUploadLimits.MAX_PHOTOS_PER_BUNCH.toLong())
    }

    @Test
    fun `one upload batch cannot pass 50 photos by uploading to different bunches at once`() {
        val (ownerId, firstBunchId) = bunchWithOwner()
        val secondBunchId = create(ownerId).id
        val batchId = UUID.randomUUID()
        val batchLimit = PhotoUploadLimits.MAX_PHOTOS_PER_BATCH
        seedPhotos(firstBunchId, batchLimit - 1, ownerId, batchId = batchId)

        val results = concurrently(
            listOf(
                { uploads.upload(command(firstBunchId, ownerId, batchId), image("batch-first")) },
                { uploads.upload(command(secondBunchId, ownerId, batchId), image("batch-second")) }
            )
        )

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(errorCodes(results)).containsExactly(ErrorCode.PHOTO_BATCH_LIMIT_EXCEEDED)
        assertThat(batchUploadCount(batchId)).isEqualTo(batchLimit.toLong())
    }

    private fun batchUploadCount(batchId: UUID): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM photo_uploaders WHERE upload_batch_id = CAST(? AS uuid)",
            Long::class.java,
            batchId.toString()
        )!!

    private fun errorCodes(results: List<Result<*>>): List<ErrorCode> =
        results.filter { it.isFailure }.map { (it.exceptionOrNull() as BusinessException).errorCode }
}
