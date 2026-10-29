package com.bunch.photo

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.photo.service.PhotoUploadKind
import com.bunch.photo.service.PhotoUploadLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

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
    fun `one member uploading to two bunches cannot pass the daily limit together`() {
        val (ownerId, firstBunchId) = bunchWithOwner()
        val secondBunchId = create(ownerId).id
        seedPhotos(firstBunchId, PhotoUploadLimits.MAX_PHOTOS_PER_MEMBER_PER_DAY - 1, ownerId)

        val results = concurrently(
            listOf(
                { uploads.upload(command(firstBunchId, ownerId), image("daily-1")) },
                { uploads.upload(command(secondBunchId, ownerId), image("daily-2")) },
                { uploads.upload(command(firstBunchId, ownerId), image("daily-3")) },
                { uploads.upload(command(secondBunchId, ownerId), image("daily-4")) }
            )
        )

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(errorCodes(results)).containsOnly(ErrorCode.PHOTO_DAILY_LIMIT_EXCEEDED)
        assertThat(memberUploadCount(ownerId)).isEqualTo(PhotoUploadLimits.MAX_PHOTOS_PER_MEMBER_PER_DAY.toLong())
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

    private fun errorCodes(results: List<Result<*>>): List<ErrorCode> =
        results.filter { it.isFailure }.map { (it.exceptionOrNull() as BusinessException).errorCode }
}
