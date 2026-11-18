package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import com.bunch.recommendation.domain.combination.TimeState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

class RecoPhotoTest {
    private val upload = RecoPhoto.Upload(memberId = 1L, uploadBatchId = UUID.randomUUID())

    @Test
    fun `a photo with a date and a time is full, with a date only it is date only, with none it is unknown`() {
        assertThat(photo(LocalDate.of(2026, 10, 19), LocalTime.of(21, 10)).timeState).isEqualTo(TimeState.FULL)
        assertThat(photo(LocalDate.of(2026, 10, 19), null).timeState).isEqualTo(TimeState.DATE_ONLY)
        assertThat(photo(null, null).timeState).isEqualTo(TimeState.UNKNOWN)
    }

    @Test
    fun `a time without a date is refused`() {
        assertThatThrownBy { photo(null, LocalTime.NOON) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a photo is uploaded by at least one member`() {
        assertThatThrownBy { photo(LocalDate.of(2026, 10, 19), null, uploads = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a photo several members uploaded together keeps all of them`() {
        val second = RecoPhoto.Upload(memberId = 2L, uploadBatchId = UUID.randomUUID())

        val photo = photo(LocalDate.of(2026, 10, 19), null, uploads = listOf(upload, second))

        assertThat(photo.uploads.map { it.memberId }).containsExactly(1L, 2L)
    }

    @Test
    fun `what the AI has not told is empty and the embedding is missing`() {
        val photo = photo(LocalDate.of(2026, 10, 19), LocalTime.NOON)

        assertThat(photo.analysis).isEqualTo(AiAnalysis.NONE)
        assertThat(photo.analysis.clues).isEmpty()
        assertThat(photo.analysis.activity).isNull()
        assertThat(photo.analysis.subject).isNull()
        assertThat(photo.analysis.people).isNull()
        assertThat(photo.embedding).isNull()
    }

    private fun photo(
        date: LocalDate?,
        time: LocalTime?,
        uploads: List<RecoPhoto.Upload> = listOf(upload)
    ) = RecoPhoto(
        photoId = 1L,
        capturedDate = date,
        capturedTime = time,
        utcOffset = ZoneOffset.ofHours(9),
        uploads = uploads,
        uploadedAt = Instant.parse("2026-10-20T00:00:00Z"),
        source = SourceType.CAMERA,
        quality = PhotoQuality.GOOD,
        aiStatus = AiStatus.DONE
    )
}
