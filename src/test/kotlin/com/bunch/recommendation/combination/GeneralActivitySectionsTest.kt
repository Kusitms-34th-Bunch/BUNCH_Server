package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.active
import com.bunch.recommendation.combination.GeneralTestSupport.sections
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.ActivityCode.CAFE
import com.bunch.recommendation.domain.combination.ActivityCode.MEAL
import com.bunch.recommendation.domain.combination.ActivityCode.UNKNOWN
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The sections of one activity made of the segments of a piece (recommended bundle specification 7-5). */
class GeneralActivitySectionsTest {
    private fun summary(photos: List<RecoPhoto>) =
        sections(photos).map { section -> section.activity to section.segments.size }

    @Test
    fun `segments side by side with the same activity are one section`() {
        val result = summary(active(1, "10:00", 3, MEAL) + active(10, "11:00", 3, MEAL))

        assertThat(result).containsExactly(MEAL to 2)
    }

    @Test
    fun `a segment of an unknown activity goes to the nearer section, which keeps its name`() {
        val photos = active(1, "10:00", 3, MEAL) + active(10, "10:40", 2, null) + active(20, "12:00", 3, CAFE)

        assertThat(summary(photos)).containsExactly(MEAL to 2, CAFE to 1)
    }

    @Test
    fun `it goes to the section before when both are as near`() {
        val photos = active(1, "10:00", 3, MEAL) + active(10, "11:00", 1, null) + active(20, "11:40", 3, CAFE)

        assertThat(summary(photos)).containsExactly(MEAL to 2, CAFE to 1)
    }

    @Test
    fun `an unknown segment at the start goes to the section after it`() {
        val photos = active(1, "10:00", 2, null) + active(10, "11:00", 3, MEAL)

        assertThat(summary(photos)).containsExactly(MEAL to 2)
    }

    @Test
    fun `sections that have the same activity once the unknown ones are put are joined`() {
        val photos = active(1, "10:00", 3, MEAL) + active(10, "10:40", 2, null) + active(20, "11:20", 3, MEAL)

        assertThat(summary(photos)).containsExactly(MEAL to 3)
    }

    @Test
    fun `unknown segments between two sections go to the nearer one each, so the sections stay in one piece`() {
        val photos = active(1, "10:00", 3, MEAL) + active(10, "10:40", 1, null) +
            active(20, "11:20", 1, null) + active(30, "12:00", 3, CAFE)

        val result = sections(photos)

        assertThat(result.map { it.activity }).containsExactly(MEAL, CAFE)
        assertThat(result.map { section -> section.photos.map { it.photoId } }).containsExactly(
            listOf(1L, 2L, 3L, 10L),
            listOf(20L, 30L, 31L, 32L)
        )
    }

    @Test
    fun `a piece with no known activity is one section of an unknown activity`() {
        val result = sections(active(1, "10:00", 2, null) + active(10, "11:00", 2, null))

        assertThat(result).hasSize(1)
        assertThat(result.single().activity).isEqualTo(UNKNOWN)
        assertThat(result.single().segments).hasSize(2)
    }

    @Test
    fun `the name of a section is a code of the table, never a made up one`() {
        val result = sections(active(1, "10:00", 3, MEAL) + active(10, "11:00", 3, CAFE))

        assertThat(result.map { it.activity }).allMatch { it in ActivityCode.entries }
    }
}
