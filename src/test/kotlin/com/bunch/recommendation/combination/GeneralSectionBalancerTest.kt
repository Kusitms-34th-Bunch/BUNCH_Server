package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.active
import com.bunch.recommendation.combination.GeneralTestSupport.counter
import com.bunch.recommendation.combination.GeneralTestSupport.day
import com.bunch.recommendation.combination.GeneralTestSupport.sections
import com.bunch.recommendation.domain.combination.ActivityCode.CAFE
import com.bunch.recommendation.domain.combination.ActivityCode.DRINKS
import com.bunch.recommendation.domain.combination.ActivityCode.MEAL
import com.bunch.recommendation.domain.combination.GeneralSectionBalancer
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Sections of different activities are only kept apart when each is enough for a bundle (specification 7-6). */
class GeneralSectionBalancerTest {
    private fun balance(photos: List<RecoPhoto>): GeneralSectionBalancer.Balanced =
        GeneralSectionBalancer().balance(sections(photos), counter(day(photos)))

    @Test
    fun `a section that is too small is joined to the other, which gives its activity when it has more photos`() {
        val result = balance(active(1, "19:00", 15, MEAL) + active(100, "21:50", 3, CAFE))

        assertThat(result.sections.map { it.activity }).containsExactly(MEAL)
        assertThat(result.rules).hasSize(1)
        assertThat(result.rules.single()).startsWith("rule6_join_section at 21:50 gap 30m")
    }

    @Test
    fun `sections that are both enough stay apart`() {
        val result = balance(active(1, "19:00", 8, MEAL) + active(100, "20:40", 12, DRINKS))

        assertThat(result.sections.map { it.activity }).containsExactly(MEAL, DRINKS)
        assertThat(result.rules).isEmpty()
    }

    @Test
    fun `the small section in the middle goes to the neighbour with the shorter gap`() {
        val photos = active(1, "10:00", 8, MEAL) + active(100, "11:50", 2, CAFE) + active(200, "14:00", 8, DRINKS)

        val result = balance(photos)

        assertThat(result.sections.map { it.activity }).containsExactly(MEAL, DRINKS)
        assertThat(result.sections.map { it.photos.size }).containsExactly(10, 8)
    }

    @Test
    fun `a piece of one section is left as it is, however small`() {
        val result = balance(active(1, "10:00", 3, MEAL))

        assertThat(result.sections).hasSize(1)
        assertThat(result.rules).isEmpty()
    }

    @Test
    fun `when two sections are as big the joined one has the activity of the earlier one`() {
        val result = balance(active(1, "10:00", 3, MEAL) + active(100, "11:00", 3, CAFE))

        assertThat(result.sections.map { it.activity }).containsExactly(MEAL)
    }

    @Test
    fun `sections side by side that have the same activity after a join are joined again`() {
        val photos = active(1, "10:00", 8, MEAL) + active(100, "11:50", 2, CAFE) + active(200, "13:30", 8, MEAL)

        val result = balance(photos)

        assertThat(result.sections).hasSize(1)
        assertThat(result.sections.single().activity).isEqualTo(MEAL)
        assertThat(result.sections.single().photos).hasSize(18)
    }
}
