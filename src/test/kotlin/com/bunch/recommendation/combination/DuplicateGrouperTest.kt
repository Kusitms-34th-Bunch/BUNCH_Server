package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.combination.PreparationTestSupport.scene
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.DuplicateGroup
import com.bunch.recommendation.domain.combination.DuplicateGrouper
import com.bunch.recommendation.domain.combination.FingerprintSimilarity
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.TimeNormalizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalTime
import java.time.ZoneOffset

/** Photos of one day that show the same picture are put into groups (recommended bundle specification 5-4). */
class DuplicateGrouperTest {
    private val times = TimeNormalizer()
    private val settings = CombinationSettings()
    private val grouper = DuplicateGrouper(
        times,
        FingerprintSimilarity,
        settings.burstMaxGap,
        settings.fingerprintBurstMinSimilarity,
        settings.sameCompositionMaxGap,
        settings.fingerprintSameCompositionMinSimilarity
    )

    private fun group(vararg photos: RecoPhoto): List<DuplicateGroup> = grouper.group(photos.map(times::start))

    private fun ids(groups: List<DuplicateGroup>): List<List<Long>> = groups.map { it.photoIds }

    // A time of the day, [seconds] after ten o'clock.
    private fun clock(seconds: Long): String = LocalTime.of(10, 0).plusSeconds(seconds).toString()

    @ParameterizedTest
    @CsvSource("0,true", "3,true", "4,false", "30,false")
    fun `pictures that are alike enough for a burst but not for the same composition are one group within 3 seconds`(
        secondsApart: Long,
        together: Boolean
    ) {
        val groups = group(
            photo(1, time = clock(0), fingerprint = scene(0)),
            photo(2, time = clock(secondsApart), fingerprint = scene(0, bitsApart = 5))
        )

        assertThat(groups.size == 1).isEqualTo(together)
    }

    @ParameterizedTest
    @CsvSource("6,true", "7,false")
    fun `a burst needs the pictures to be as alike as 0_90, six bits apart and not seven`(
        bitsApart: Int,
        together: Boolean
    ) {
        val groups = group(
            photo(1, time = "10:00:00", fingerprint = scene(0)),
            photo(2, time = "10:00:02", fingerprint = scene(0, bitsApart = bitsApart))
        )

        assertThat(groups.size == 1).isEqualTo(together)
    }

    @ParameterizedTest
    @CsvSource("119,true", "120,true", "121,false")
    fun `the same composition is one group when taken within 120 seconds`(secondsApart: Long, together: Boolean) {
        val groups = group(
            photo(1, time = clock(0), fingerprint = scene(0)),
            photo(2, time = clock(secondsApart), fingerprint = scene(0, bitsApart = 1))
        )

        assertThat(groups.size == 1).isEqualTo(together)
    }

    @ParameterizedTest
    @CsvSource("3,true", "4,false")
    fun `the same composition needs the pictures to be as alike as 0_95, three bits apart and not four`(
        bitsApart: Int,
        together: Boolean
    ) {
        val groups = group(
            photo(1, time = "10:00:00", fingerprint = scene(0)),
            photo(2, time = "10:01:00", fingerprint = scene(0, bitsApart = bitsApart))
        )

        assertThat(groups.size == 1).isEqualTo(together)
    }

    @Test
    fun `members who took the same picture are one group whoever they are`() {
        val groups = group(
            photo(1, time = "10:00:00", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "10:00:30", members = listOf(2), fingerprint = scene(0, bitsApart = 1))
        )

        assertThat(groups).hasSize(1)
        assertThat(groups.single().uploaderIds).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun `the uploaders of a group are all that uploaded any photo of it, a photo uploaded together included`() {
        val groups = group(
            photo(1, time = "10:00:00", members = listOf(1, 2), fingerprint = scene(0)),
            photo(2, time = "10:00:05", members = listOf(3), fingerprint = scene(0, bitsApart = 1))
        )

        assertThat(groups.single().uploaderIds).containsExactlyInAnyOrder(1L, 2L, 3L)
    }

    @Test
    fun `groups are joined as far as the chain goes`() {
        val groups = group(
            photo(1, time = "10:00:00", fingerprint = scene(0)),
            photo(2, time = "10:01:30", fingerprint = scene(0, bitsApart = 3)),
            photo(3, time = "10:03:00", fingerprint = scene(0, bitsApart = 6))
        )

        assertThat(ids(groups)).containsExactly(listOf(1L, 2L, 3L))
        assertThat(groups.single().id).isEqualTo(1L)
    }

    @Test
    fun `photos of another scene stay apart`() {
        val groups = group(
            photo(1, time = "10:00:00", fingerprint = scene(0)),
            photo(2, time = "10:00:01", fingerprint = scene(1)),
            photo(3, time = "10:00:02", fingerprint = scene(0, bitsApart = 1))
        )

        assertThat(ids(groups)).containsExactly(listOf(1L, 3L), listOf(2L))
    }

    @Test
    fun `a photo without a fingerprint is never joined`() {
        val groups = group(
            photo(1, time = "10:00:00", fingerprint = null),
            photo(2, time = "10:00:01", fingerprint = null),
            photo(3, time = "10:00:02", fingerprint = scene(0)),
            photo(4, time = "10:00:03", fingerprint = scene(0))
        )

        assertThat(ids(groups)).containsExactly(listOf(1L), listOf(2L), listOf(3L, 4L))
    }

    @Test
    fun `a photo without a time is a group of its own`() {
        val groups = group(
            photo(1, time = "10:00:00", fingerprint = scene(0)),
            photo(2, time = null, fingerprint = scene(0))
        )

        assertThat(ids(groups)).containsExactly(listOf(1L), listOf(2L))
    }

    @Test
    fun `the gap is measured on the absolute clock, whatever the local clocks say`() {
        val groups = group(
            photo(1, date = "2026-10-19", time = "09:00:00", offset = ZoneOffset.ofHours(9), fingerprint = scene(0)),
            photo(2, date = "2026-10-18", time = "19:00:02", offset = ZoneOffset.ofHours(-5), fingerprint = scene(0))
        )

        assertThat(groups).hasSize(1)
    }

    @Test
    fun `the groups come in the order of their first photo and the photos of a group in the order of the time`() {
        val groups = group(
            photo(9, time = "11:00:00", fingerprint = scene(1)),
            photo(7, time = "10:00:02", fingerprint = scene(0, bitsApart = 1)),
            photo(8, time = "10:00:00", fingerprint = scene(0)),
            photo(5, time = null, fingerprint = scene(2))
        )

        assertThat(ids(groups)).containsExactly(listOf(8L, 7L), listOf(9L), listOf(5L))
        assertThat(groups.map { it.id }).containsExactly(7L, 9L, 5L)
    }

    @Test
    fun `the same photos give the same groups whatever the order they come in`() {
        val photos = listOf(
            photo(1, time = "10:00:00", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "10:00:30", members = listOf(2), fingerprint = scene(0, bitsApart = 1)),
            photo(3, time = "10:05:00", members = listOf(1), fingerprint = scene(1)),
            photo(4, time = null, members = listOf(3), fingerprint = null)
        )

        val reversed = grouper.group(photos.reversed().map(times::start))

        assertThat(reversed).isEqualTo(grouper.group(photos.map(times::start)))
    }
}
