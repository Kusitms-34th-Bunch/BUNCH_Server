package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.active
import com.bunch.recommendation.combination.GeneralTestSupport.day
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.combination.PreparationTestSupport.scene
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.ActivityCode.CAFE
import com.bunch.recommendation.domain.combination.ActivityCode.MEAL
import com.bunch.recommendation.domain.combination.ActivityCode.UNKNOWN
import com.bunch.recommendation.domain.combination.DayPart
import com.bunch.recommendation.domain.combination.GeneralActivities
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** What the general bundles read from the activity of the photos (recommended bundle specification 4-3, 5-1, 7-3). */
class GeneralActivitiesTest {
    private val activities = GeneralActivities()

    private fun prepared(photos: List<RecoPhoto>) = day(photos).photos

    private fun representative(photos: List<RecoPhoto>) = activities.representative(prepared(photos))

    @ParameterizedTest
    @CsvSource("0.49,UNKNOWN", "0.50,MEAL", "0.95,MEAL")
    fun `a reading below 0_50 counts as an unknown activity`(confidence: Double, expected: ActivityCode) {
        val photos = prepared(active(1, "12:00", 1, MEAL, confidence = confidence))

        assertThat(activities.activityOf(photos.single())).isEqualTo(expected)
    }

    @Test
    fun `a photo that has no reading has an unknown activity`() {
        assertThat(activities.activityOf(prepared(active(1, "12:00", 1, null)).single())).isEqualTo(UNKNOWN)
    }

    @Test
    fun `the main activity is the one most of the photos show`() {
        val photos = active(1, "12:00", 3, MEAL) + active(10, "12:30", 2, CAFE)

        assertThat(representative(photos)).isEqualTo(MEAL)
    }

    @Test
    fun `the activity is unknown when half of the photos or more are unknown`() {
        assertThat(representative(active(1, "12:00", 2, MEAL) + active(10, "12:30", 2, null))).isEqualTo(UNKNOWN)
        assertThat(representative(active(1, "12:00", 3, MEAL) + active(10, "12:30", 2, null))).isEqualTo(MEAL)
    }

    @Test
    fun `a tie goes to the activity with the bigger sum of confidence, then to the first of the table`() {
        val surer = active(1, "12:00", 2, MEAL, confidence = 0.6) + active(10, "12:30", 2, CAFE, confidence = 0.9)
        val alike = active(1, "12:00", 2, CAFE) + active(10, "12:30", 2, MEAL)

        assertThat(representative(surer)).isEqualTo(CAFE)
        assertThat(representative(alike)).isEqualTo(MEAL)
    }

    @Test
    fun `only usable photos vote`() {
        val blurred = active(1, "12:00", 3, CAFE).map { it.copy(quality = PhotoQuality.LOW) }

        assertThat(representative(blurred + active(10, "12:30", 1, MEAL))).isEqualTo(MEAL)
        assertThat(representative(blurred)).isEqualTo(UNKNOWN)
    }

    @Test
    fun `a photo of the group of a burst is one vote each`() {
        val cafe = active(1, "12:00", 1, CAFE)[0].analysis
        val burst = (1L..3L).map { photo(it, time = "12:00:0$it", fingerprint = scene(0), analysis = cafe) }
        val others = active(10, "12:30", 2, MEAL)

        assertThat(representative(burst + others)).isEqualTo(CAFE)
    }

    @Test
    fun `the part of the day is the one most of the photos were taken in, and the earlier one for a tie`() {
        val morning = active(1, "11:00", 2, MEAL, step = 10) + active(10, "12:00", 1, MEAL)

        assertThat(activities.slotOf(prepared(morning))).isEqualTo(DayPart.MORNING)
        assertThat(activities.slotOf(prepared(active(1, "11:30", 1, MEAL) + active(10, "12:30", 1, MEAL))))
            .isEqualTo(DayPart.MORNING)
    }
}
