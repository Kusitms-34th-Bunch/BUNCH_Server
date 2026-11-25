package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.ClockErrorFilter
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.TimeNormalizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * A shooting time later than the upload means the clock of the device is wrong, and the upload batch is left out of the
 * calculation (recommended bundle specification, exception X5).
 */
class ClockErrorFilterTest {
    private val times = TimeNormalizer()
    private val filter = ClockErrorFilter(times, CombinationSettings().clockErrorMargin)

    // The upload is at midnight UTC of the 19th, which is 09:00 in Korea (the default zone of a photo without offset).
    private val uploaded = Instant.parse("2026-10-19T00:00:00Z")

    private fun wrong(vararg photos: RecoPhoto): Set<Long> = filter.find(photos.map(times::start))

    private fun shot(
        id: Long,
        date: String,
        time: String?,
        offset: ZoneOffset? = null,
        members: List<Long> = listOf(1),
        batch: UUID? = null,
        uploadedAt: Instant = uploaded
    ): RecoPhoto = photo(
        id,
        date = date,
        time = time,
        offset = offset,
        members = members,
        batch = batch,
        memberUploadedAt = members.associateWith { uploadedAt }
    )

    @ParameterizedTest
    @CsvSource("2026-10-19,22:59:59,false", "2026-10-19,23:00:00,false", "2026-10-19,23:00:01,true")
    fun `without an offset a photo may be taken up to 14 hours after its upload`(
        date: String,
        time: String,
        isWrong: Boolean
    ) {
        // 23:00:00 in Korea is 14:00:00 UTC, exactly 14 hours after the upload.
        assertThat(wrong(shot(1, date, time)).isNotEmpty()).isEqualTo(isWrong)
    }

    @ParameterizedTest
    @CsvSource("2026-10-19,08:59:59,false", "2026-10-19,09:00:00,false", "2026-10-19,09:00:01,true")
    fun `with an offset a photo may not be taken after its upload at all`(
        date: String,
        time: String,
        isWrong: Boolean
    ) {
        val korea = ZoneOffset.ofHours(9)

        assertThat(wrong(shot(1, date, time, offset = korea)).isNotEmpty()).isEqualTo(isWrong)
    }

    @Test
    fun `the whole upload batch is left out, the photos taken right and the ones without a time too`() {
        val batch = UUID.nameUUIDFromBytes("one".toByteArray())

        val result = wrong(
            shot(1, "2026-10-19", "23:00:01", batch = batch),
            shot(2, "2026-10-19", "09:00", batch = batch),
            shot(3, "2026-10-19", null, batch = batch)
        )

        assertThat(result).containsExactlyInAnyOrder(1L, 2L, 3L)
    }

    @Test
    fun `another batch of the same member and the photos of other members are not touched`() {
        val result = wrong(
            shot(1, "2026-10-19", "23:00:01", batch = UUID.nameUUIDFromBytes("a".toByteArray())),
            shot(2, "2026-10-19", "09:00", batch = UUID.nameUUIDFromBytes("b".toByteArray())),
            shot(3, "2026-10-19", "09:00", members = listOf(2))
        )

        assertThat(result).containsExactly(1L)
    }

    @Test
    fun `a photo without a time cannot show a wrong clock`() {
        assertThat(wrong(shot(1, "2026-10-25", null))).isEmpty()
    }

    @Test
    fun `a photo that two members uploaded stays when one of the batches is right`() {
        val late = Instant.parse("2026-10-21T00:00:00Z")
        val shared = photo(
            1, date = "2026-10-19", time = "23:00:01", members = listOf(1, 2),
            memberUploadedAt = mapOf(1L to uploaded, 2L to late)
        )

        // The batch of member 1 is wrong, the one of member 2 is not: the photo was uploaded right by member 2.
        assertThat(wrong(shared)).isEmpty()
    }

    @Test
    fun `a photo that two members uploaded is left out when both batches are wrong`() {
        val shared = photo(
            1, date = "2026-10-19", time = "23:00:01", members = listOf(1, 2),
            memberUploadedAt = mapOf(1L to uploaded, 2L to uploaded)
        )

        assertThat(wrong(shared)).containsExactly(1L)
    }

    @Test
    fun `photos taken before their upload are never wrong, however long before`() {
        assertThat(wrong(shot(1, "2026-09-01", "10:00"), shot(2, "2026-10-19", "00:00"))).isEmpty()
    }

    @Test
    fun `no photos is no wrong clock`() {
        assertThat(filter.find(emptyList())).isEmpty()
    }
}
