package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.combination.PreparationTestSupport.scene
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.DateBorrower
import com.bunch.recommendation.domain.combination.FingerprintSimilarity
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.TimeNormalizer
import com.bunch.recommendation.domain.combination.TimedPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/** A photo without a date or a time borrows it from a photo that looks the same (recommended bundle spec 5-3). */
class DateBorrowerTest {
    private val times = TimeNormalizer()
    private val borrower = DateBorrower(
        times,
        FingerprintSimilarity,
        CombinationSettings().fingerprintSameCompositionMinSimilarity
    )

    private fun borrow(vararg photos: RecoPhoto): List<TimedPhoto> =
        borrower.borrow(photos.map(times::start)).sortedBy { it.photoId }

    @Test
    fun `a photo without a date takes the date and the time of the photo that looks the same`() {
        val result = borrow(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = null, fingerprint = scene(0, bitsApart = 1))
        )

        val borrowed = result.last()
        assertThat(borrowed.date).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(borrowed.time).isEqualTo(LocalTime.of(21, 10))
        assertThat(borrowed.borrowedFrom).isEqualTo(1L)
        assertThat(borrowed.photo.capturedDate).isNull()
    }

    @Test
    fun `a photo with a date only keeps its date and takes the time`() {
        val result = borrow(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = "2026-10-25", fingerprint = scene(0))
        )

        val borrowed = result.last()
        assertThat(borrowed.date).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(borrowed.time).isEqualTo(LocalTime.of(21, 10))
    }

    @Test
    fun `the time comes with the offset of the photo it was borrowed from`() {
        val result = borrow(
            photo(1, date = "2026-10-19", time = "21:10", offset = ZoneOffset.ofHours(-5), fingerprint = scene(0)),
            photo(2, date = null, fingerprint = scene(0))
        )

        assertThat(result.last().offset).isEqualTo(ZoneOffset.ofHours(-5))
    }

    @Test
    fun `a photo that nothing looks like stays without a date`() {
        val result = borrow(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = null, fingerprint = scene(1))
        )

        assertThat(result.last().date).isNull()
        assertThat(result.last().borrowedFrom).isNull()
    }

    @Test
    fun `a photo without a fingerprint cannot borrow and cannot lend by its looks`() {
        val result = borrow(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = null, fingerprint = null),
            photo(3, date = "2026-10-19", time = "21:30", fingerprint = null),
            photo(4, date = null, fingerprint = scene(0))
        )

        assertThat(result.first { it.photoId == 2L }.date).isNull()
        assertThat(result.first { it.photoId == 4L }.borrowedFrom).isEqualTo(1L)
    }

    @Test
    fun `a photo that looks just a little different is still the same`() {
        val near = borrow(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = null, fingerprint = scene(0, bitsApart = 3))
        )
        val far = borrow(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = null, fingerprint = scene(0, bitsApart = 4))
        )

        assertThat(near.last().date).isNotNull()
        assertThat(far.last().date).isNull()
    }

    @Test
    fun `of several photos that look the same the most alike one lends`() {
        val result = borrow(
            photo(1, date = "2026-10-19", time = "09:00", fingerprint = scene(0, bitsApart = 3)),
            photo(2, date = "2026-10-19", time = "10:00", fingerprint = scene(0, bitsApart = 1)),
            photo(3, date = null, fingerprint = scene(0))
        )

        assertThat(result.last().borrowedFrom).isEqualTo(2L)
        assertThat(result.last().time).isEqualTo(LocalTime.of(10, 0))
    }

    @Test
    fun `when they are as alike the one uploaded in the same batch lends, then the earlier one, then the lower ID`() {
        val batch = UUID.randomUUID()
        val sameBatch = borrow(
            photo(1, date = "2026-10-19", time = "09:00", fingerprint = scene(0)),
            photo(2, date = "2026-10-19", time = "10:00", fingerprint = scene(0), batch = batch),
            photo(3, date = null, fingerprint = scene(0), batch = batch)
        )
        val earlier = borrow(
            photo(5, date = "2026-10-19", time = "10:00", fingerprint = scene(0)),
            photo(4, date = "2026-10-19", time = "09:00", fingerprint = scene(0)),
            photo(6, date = null, fingerprint = scene(0))
        )
        val lowerId = borrow(
            photo(8, date = "2026-10-19", time = "09:00", fingerprint = scene(0)),
            photo(7, date = "2026-10-19", time = "09:00", fingerprint = scene(0)),
            photo(9, date = null, fingerprint = scene(0))
        )

        assertThat(sameBatch.last().borrowedFrom).isEqualTo(2L)
        assertThat(earlier.last().borrowedFrom).isEqualTo(4L)
        assertThat(lowerId.last().borrowedFrom).isEqualTo(7L)
    }

    @Test
    fun `a date is not passed on from a photo that borrowed it`() {
        val result = borrow(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = null, fingerprint = scene(0, bitsApart = 3)),
            photo(3, date = null, fingerprint = scene(0, bitsApart = 6))
        )

        assertThat(result.first { it.photoId == 2L }.date).isNotNull()
        assertThat(result.first { it.photoId == 3L }.date).isNull()
    }

    @Test
    fun `a photo with a date and a time is left as it is`() {
        val result = borrow(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = "2026-10-20", time = "08:00", fingerprint = scene(0))
        )

        assertThat(result.map { it.borrowedFrom }).containsOnlyNulls()
        assertThat(result.last().time).isEqualTo(LocalTime.of(8, 0))
    }
}
