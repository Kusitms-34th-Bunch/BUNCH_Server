package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.combination.PreparationTestSupport.scene
import com.bunch.recommendation.domain.combination.ClockCorrector
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.FingerprintSimilarity
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.TimeNormalizer
import com.bunch.recommendation.domain.combination.TimedPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalTime

/** The clock of a member that is wrong is corrected by the photos of the same scene of the others (spec 5-5). */
class ClockCorrectorTest {
    private val times = TimeNormalizer()
    private val settings = CombinationSettings()
    private val corrector = ClockCorrector(
        times,
        FingerprintSimilarity,
        settings.fingerprintSameCompositionMinSimilarity,
        settings.clockCorrectionMinPairs,
        settings.clockCorrectionMinMedian
    )

    private fun correct(vararg photos: RecoPhoto): Map<Long, TimedPhoto> =
        corrector.correct(photos.map(times::start)).associateBy { it.photoId }

    // Two members shoot the same two scenes, the second one with a clock that is [slow] minutes behind.
    private fun twoScenes(slow: Long): Array<RecoPhoto> {
        val behind = { time: String, minutes: Long -> LocalTime.parse(time).minusMinutes(minutes).toString() }
        return arrayOf(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "10:10", members = listOf(1), fingerprint = scene(1)),
            photo(3, time = behind("10:00", slow), members = listOf(2), fingerprint = scene(0, bitsApart = 1)),
            photo(4, time = behind("10:10", slow), members = listOf(2), fingerprint = scene(1, bitsApart = 1))
        )
    }

    @Test
    fun `a clock that is 30 minutes slow is moved when two photos of the same scene show it`() {
        val result = correct(*twoScenes(slow = 30))

        assertThat(result.getValue(3).shift).isEqualTo(Duration.ofMinutes(30))
        assertThat(result.getValue(4).shift).isEqualTo(Duration.ofMinutes(30))
        assertThat(times.instant(result.getValue(3))).isEqualTo(times.instant(result.getValue(1)))
        assertThat(result.getValue(1).shift).isEqualTo(Duration.ZERO)
        assertThat(result.getValue(2).shift).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `a clock that is fast is moved back`() {
        val result = correct(*twoScenes(slow = -45))

        assertThat(result.getValue(3).shift).isEqualTo(Duration.ofMinutes(-45))
    }

    @Test
    fun `one pair is not enough`() {
        val result = correct(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0)),
            photo(3, time = "09:30", members = listOf(2), fingerprint = scene(0))
        )

        assertThat(result.values.map { it.shift }).containsOnly(Duration.ZERO)
    }

    @Test
    fun `a difference of nine minutes is left and ten is moved`() {
        assertThat(correct(*twoScenes(slow = 9)).values.map { it.shift }).containsOnly(Duration.ZERO)
        assertThat(correct(*twoScenes(slow = 10)).getValue(3).shift).isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun `nothing is moved when there is no photo of the same scene to compare`() {
        val result = correct(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "10:10", members = listOf(1), fingerprint = scene(1)),
            photo(3, time = "09:00", members = listOf(2), fingerprint = scene(2)),
            photo(4, time = "09:10", members = listOf(2), fingerprint = scene(3))
        )

        assertThat(result.values.map { it.shift }).containsOnly(Duration.ZERO)
    }

    @Test
    fun `a photo without a fingerprint is not compared`() {
        val result = correct(
            photo(1, time = "10:00", members = listOf(1), fingerprint = null),
            photo(2, time = "10:10", members = listOf(1), fingerprint = null),
            photo(3, time = "09:30", members = listOf(2), fingerprint = null),
            photo(4, time = "09:40", members = listOf(2), fingerprint = null)
        )

        assertThat(result.values.map { it.shift }).containsOnly(Duration.ZERO)
    }

    @Test
    fun `the median of the differences is used, so one odd pair does not decide`() {
        val result = correct(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "10:10", members = listOf(1), fingerprint = scene(1)),
            photo(3, time = "10:20", members = listOf(1), fingerprint = scene(2)),
            photo(4, time = "09:30", members = listOf(2), fingerprint = scene(0)),
            photo(5, time = "09:40", members = listOf(2), fingerprint = scene(1)),
            photo(6, time = "08:00", members = listOf(2), fingerprint = scene(2))
        )

        assertThat(result.getValue(4).shift).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `of an even number of differences the median is the mean of the two in the middle`() {
        val result = correct(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "10:10", members = listOf(1), fingerprint = scene(1)),
            photo(3, time = "09:30", members = listOf(2), fingerprint = scene(0)),
            photo(4, time = "09:45", members = listOf(2), fingerprint = scene(1))
        )

        assertThat(result.getValue(3).shift).isEqualTo(Duration.ofMinutes(27).plusSeconds(30))
    }

    @Test
    fun `when two members differ equally the one with fewer photos that day is moved`() {
        val result = correct(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "10:10", members = listOf(1), fingerprint = scene(1)),
            photo(5, time = "10:20", members = listOf(1), fingerprint = scene(2)),
            photo(3, time = "09:30", members = listOf(2), fingerprint = scene(0)),
            photo(4, time = "09:40", members = listOf(2), fingerprint = scene(1))
        )

        assertThat(result.getValue(3).shift).isEqualTo(Duration.ofMinutes(30))
        assertThat(result.getValue(4).shift).isEqualTo(Duration.ofMinutes(30))
        assertThat(result.getValue(1).shift).isEqualTo(Duration.ZERO)
        assertThat(result.getValue(5).shift).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `when nothing tells whose clock is wrong the member with the higher ID is moved`() {
        val result = correct(
            photo(1, time = "09:30", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "09:40", members = listOf(1), fingerprint = scene(1)),
            photo(3, time = "10:00", members = listOf(2), fingerprint = scene(0)),
            photo(4, time = "10:10", members = listOf(2), fingerprint = scene(1))
        )

        assertThat(result.getValue(3).shift).isEqualTo(Duration.ofMinutes(-30))
        assertThat(result.getValue(1).shift).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `of three members the one who differs from the other two is moved`() {
        val result = correct(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "10:10", members = listOf(1), fingerprint = scene(1)),
            photo(3, time = "10:00", members = listOf(2), fingerprint = scene(0)),
            photo(4, time = "10:10", members = listOf(2), fingerprint = scene(1)),
            photo(5, time = "10:40", members = listOf(3), fingerprint = scene(0)),
            photo(6, time = "10:50", members = listOf(3), fingerprint = scene(1))
        )

        assertThat(result.getValue(5).shift).isEqualTo(Duration.ofMinutes(-40))
        assertThat(result.getValue(6).shift).isEqualTo(Duration.ofMinutes(-40))
        assertThat(listOf(1L, 2L, 3L, 4L).map { result.getValue(it).shift }).containsOnly(Duration.ZERO)
    }

    @Test
    fun `only the photos of the member from that day are moved, not a photo several members uploaded`() {
        val result = correct(
            *twoScenes(slow = 30),
            photo(7, time = "11:00", members = listOf(2, 3), fingerprint = scene(2)),
            photo(8, date = "2026-10-20", time = "12:00", members = listOf(2), fingerprint = scene(3))
        )

        assertThat(result.getValue(3).shift).isEqualTo(Duration.ofMinutes(30))
        assertThat(result.getValue(7).shift).isEqualTo(Duration.ZERO)
        assertThat(result.getValue(8).shift).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `a photo several members uploaded is not the own photo of any of them`() {
        val result = correct(
            photo(1, time = "10:00", members = listOf(1, 2), fingerprint = scene(0)),
            photo(2, time = "10:10", members = listOf(1, 2), fingerprint = scene(1)),
            photo(3, time = "09:00", members = listOf(3), fingerprint = scene(0)),
            photo(4, time = "09:10", members = listOf(3), fingerprint = scene(1))
        )

        assertThat(result.getValue(1).shift).isEqualTo(Duration.ZERO)
        assertThat(result.getValue(3).shift).isEqualTo(Duration.ofMinutes(60))
    }

    @Test
    fun `a photo without a time is not moved`() {
        val result = correct(
            *twoScenes(slow = 30),
            photo(9, time = null, members = listOf(2))
        )

        assertThat(result.getValue(9).shift).isEqualTo(Duration.ZERO)
    }
}
