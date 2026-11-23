package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.BundleEligibility
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import com.bunch.recommendation.domain.combination.TimeRange
import com.bunch.recommendation.domain.combination.TogetherMoments
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalTime

/**
 * The moments in which two members took photos together (recommended bundle specification 2-1, R2; cases G5, G22, G25,
 * C8 and X10).
 */
class TogetherMomentsTest {
    private val eligibility = BundleEligibility()
    private val together = TogetherMoments(CombinationSettings().togetherWindow, eligibility::isUsable)

    private fun find(vararg photos: RecoPhoto): TogetherMoments.Result {
        val day = ready(photos.toList())
        return together.find(day.photos, day.duplicateGroups)
    }

    // A photo of one member at [time] of the 19th of October; the photo number is also the order of the upload.
    private fun shot(id: Long, time: String, member: Long = 1): RecoPhoto =
        photo(id, time = time, members = listOf(member))

    private fun at(time: String): Instant = Instant.parse("2026-10-19T${time}Z").minusSeconds(9 * 3600)

    // The times of a run of photos, one every [stepMinutes], from [start] to [end], as photos of [member].
    private fun run(firstId: Long, start: String, end: String, member: Long, stepMinutes: Long = 5): List<RecoPhoto> {
        val times = generateSequence(LocalTime.parse(start)) { it.plusMinutes(stepMinutes) }
            .takeWhile { !it.isAfter(LocalTime.parse(end)) }.toList()
        return times.mapIndexed { index, time -> shot(firstId + index, time.toString(), member) }
    }

    @Test
    fun `G5 photos that two members took at far apart times are not taken together`() {
        val morning = run(1, "09:00", "09:45", member = 1)
        val night = listOf(shot(50, "22:00", member = 2), shot(51, "22:10", member = 2))

        val result = find(*(morning + night).toTypedArray())

        assertThat(result.exists).isFalse()
        assertThat(result.photoIds).isEmpty()
        assertThat(result.moments).isEmpty()
    }

    @Test
    fun `G22 one member shooting first and the other 25 minutes later are together`() {
        val first = run(1, "19:00", "19:40", member = 1)
        val second = run(10, "20:05", "21:00", member = 2)

        val result = find(*(first + second).toTypedArray())

        // 19:35 and 19:40 are within 30 minutes of 20:05, and 19:40 of 20:10; nothing else is near the other member.
        assertThat(result.photoIds).containsExactlyInAnyOrder(8L, 9L, 10L, 11L)
        assertThat(result.moments).containsExactly(TimeRange(at("19:35:00"), at("20:10:00")))
    }

    @Test
    fun `G25 photos of two members that are 40 minutes apart are not together`() {
        val result = find(shot(1, "14:00", 1), shot(2, "14:10", 1), shot(3, "14:20", 1), shot(4, "15:00", 2))

        assertThat(result.exists).isFalse()
    }

    @ParameterizedTest
    @CsvSource("10:29:59,true", "10:30:00,true", "10:30:01,false")
    fun `the photos of two members are together up to exactly 30 minutes apart`(time: String, expected: Boolean) {
        val result = find(shot(1, "10:00:00", 1), shot(2, time, 2))

        assertThat(result.exists).isEqualTo(expected)
    }

    @Test
    fun `C8 two members with the same phone shooting in the same second are together`() {
        val first = photo(1, time = "10:00:05", members = listOf(1), millis = 120, model = "A", size = 4032 to 3024)
        val second = photo(2, time = "10:00:05", members = listOf(2), millis = 480, model = "A", size = 4032 to 3024)

        val result = find(first, second)

        assertThat(result.photoIds).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun `X10 all members shooting at one time and nothing else the whole day is a together moment`() {
        val result = find(
            shot(1, "12:00:00", 1), shot(2, "12:00:10", 2), shot(3, "12:00:20", 3), shot(4, "12:00:30", 1)
        )

        assertThat(result.exists).isTrue()
        assertThat(result.photoIds).containsExactlyInAnyOrder(1L, 2L, 3L, 4L)
    }

    @Test
    fun `a member alone is not together with themselves`() {
        assertThat(find(shot(1, "10:00", 1), shot(2, "10:05", 1), shot(3, "10:10", 1)).exists).isFalse()
    }

    @Test
    fun `a photo that another member only uploaded again is not a sign of being together`() {
        val first = Instant.parse("2030-01-01T00:00:00Z")
        val again = Instant.parse("2030-01-02T00:00:00Z")
        val photos = (1L..4L).map {
            photo(it, time = "10:0$it", members = listOf(1, 2), memberUploadedAt = mapOf(1L to first, 2L to again))
        }

        val result = find(*photos.toTypedArray())

        assertThat(result.exists).isFalse()
    }

    @Test
    fun `a copy of the same original that another member saved and uploaded counts for the one who uploaded first`() {
        val original = photo(
            1, time = "10:00:00", members = listOf(1), millis = 120, model = "A", size = 4032 to 3024,
            memberUploadedAt = mapOf(1L to Instant.parse("2030-01-01T00:00:00Z"))
        )
        val saved = photo(
            2, time = "10:00:00", members = listOf(2), millis = 120, model = "A", size = 4032 to 3024,
            memberUploadedAt = mapOf(2L to Instant.parse("2030-01-02T00:00:00Z"))
        )

        assertThat(find(original, saved).exists).isFalse()
    }

    @Test
    fun `when two members uploaded at the same time the lower member number is the one it counts for`() {
        val same = Instant.parse("2030-01-01T00:00:00Z")
        val both = photo(1, time = "10:00", members = listOf(2, 1), memberUploadedAt = mapOf(1L to same, 2L to same))
        val sameMember = shot(2, "10:05", 1)
        val otherMember = shot(3, "10:05", 2)

        assertThat(find(both, sameMember).exists).isFalse()
        assertThat(find(both, otherMember).exists).isTrue()
    }

    @Test
    fun `a photo that is not usable and a photo without a time are not counted`() {
        val screenshot = shot(2, "10:05", 2).copy(source = SourceType.SCREENSHOT)
        val blurred = shot(3, "10:06", 2).copy(quality = PhotoQuality.LOW)
        val dateOnly = photo(4, time = null, members = listOf(2))

        assertThat(find(shot(1, "10:00", 1), screenshot, blurred, dateOnly).exists).isFalse()
    }

    @Test
    fun `only the photos that are given are looked at, the others of the day are not counted`() {
        val day = ready(listOf(shot(1, "10:00", 1), shot(2, "10:05", 2), shot(3, "10:10", 1)))
        val ofOneMember = day.photos.filter { it.photoId != 2L }

        assertThat(together.find(day.photos, day.duplicateGroups).exists).isTrue()
        assertThat(together.find(ofOneMember, day.duplicateGroups).exists).isFalse()
    }

    @Test
    fun `stretches that overlap are one moment and stretches far apart are two`() {
        val close = find(shot(1, "10:00", 1), shot(2, "10:10", 2), shot(3, "10:20", 1))
        val far = find(shot(1, "10:00", 1), shot(2, "10:10", 2), shot(3, "15:00", 1), shot(4, "15:10", 2))

        assertThat(close.moments).containsExactly(TimeRange(at("10:00:00"), at("10:20:00")))
        assertThat(far.moments).containsExactly(
            TimeRange(at("10:00:00"), at("10:10:00")),
            TimeRange(at("15:00:00"), at("15:10:00"))
        )
    }

    @Test
    fun `the same photos give the same answer whatever the order they come in`() {
        val day = ready(
            listOf(shot(1, "10:00", 1), shot(2, "10:20", 2), shot(3, "10:40", 1), shot(4, "15:00", 3))
        )

        assertThat(together.find(day.photos.reversed(), day.duplicateGroups))
            .isEqualTo(together.find(day.photos, day.duplicateGroups))
    }

    @Test
    fun `no photos is no moment`() {
        assertThat(together.find(emptyList(), emptyList()).exists).isFalse()
    }
}
