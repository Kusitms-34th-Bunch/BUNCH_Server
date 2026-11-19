package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.ListCandidate
import com.bunch.recommendation.domain.combination.RecommendedListRules
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

/** Which recommended bundles the list shows and in what order (specification 10-5, photo box 4.2.9 rule 6). */
class RecommendedListRulesTest {
    private val rules = RecommendedListRules()
    private val first = LocalDate.parse("2026-10-01")

    private fun general(
        id: Long,
        day: Int,
        activity: ActivityCode? = ActivityCode.MEAL,
        group: Boolean = false,
        hour: Long = 12,
        members: Int = 2,
        photos: Int = 8,
        added: Instant? = null
    ) = Instant.parse("2026-10-01T00:00:00Z").plusSeconds(day * 86_400L + hour * 3_600L).let { start ->
        ListCandidate(
            id = id,
            kind = BundleKind.GENERAL,
            dayKey = first.plusDays(day.toLong()),
            startAt = start,
            activity = activity,
            hasGroupPhoto = group,
            lastPhotoAddedAt = added ?: start,
            uploaderCount = members,
            photoCount = photos
        )
    }

    private fun event(id: Long, day: Int, hour: Long = 12) =
        general(id, day, activity = null, hour = hour).copy(kind = BundleKind.EVENT)

    // --- the order ---

    @Test
    fun `the bundle whose last photo was added most recently comes first, whatever its kind`() {
        val candidates = listOf(
            general(1, day = 5, activity = ActivityCode.MEAL),
            event(2, day = 1),
            general(3, day = 9, activity = ActivityCode.CAFE),
            event(4, day = 7)
        )

        assertThat(rules.select(candidates)).containsExactly(3L, 4L, 1L, 2L)
    }

    @Test
    fun `on the same day the later start comes first`() {
        val candidates = listOf(
            general(1, day = 3, activity = ActivityCode.MEAL, hour = 9),
            general(2, day = 3, activity = ActivityCode.CAFE, hour = 20),
            general(3, day = 3, activity = ActivityCode.WALK, hour = 14)
        )

        assertThat(rules.select(candidates)).containsExactly(2L, 3L, 1L)
    }

    @Test
    fun `a photo of an old day that was uploaded lately puts its bundle on top`() {
        val lately = Instant.parse("2026-12-01T00:00:00Z")
        val candidates = listOf(
            general(1, day = 20, activity = ActivityCode.MEAL),
            general(2, day = 1, activity = ActivityCode.CAFE, added = lately),
            general(3, day = 10, activity = ActivityCode.WALK)
        )

        assertThat(rules.select(candidates)).containsExactly(2L, 1L, 3L)
    }

    @Test
    fun `with the same last photo added the bundle with more members comes first, then the one with more photos`() {
        val same = Instant.parse("2026-11-01T00:00:00Z")
        val candidates = listOf(
            general(1, day = 1, activity = ActivityCode.MEAL, members = 2, photos = 9, added = same),
            general(2, day = 1, activity = ActivityCode.CAFE, members = 3, photos = 6, added = same),
            general(3, day = 1, activity = ActivityCode.WALK, members = 2, photos = 12, added = same)
        )

        assertThat(rules.select(candidates)).containsExactly(2L, 3L, 1L)
    }

    @Test
    fun `bundles that cannot be told apart come in the order of their numbers, the greater first`() {
        val candidates = listOf(
            general(1, day = 3, activity = ActivityCode.MEAL),
            general(2, day = 3, activity = ActivityCode.CAFE),
            general(3, day = 3, activity = ActivityCode.WALK)
        )

        assertThat(rules.select(candidates)).containsExactly(3L, 2L, 1L)
    }

    @Test
    fun `the same bundles are listed the same way whatever the order they come in`() {
        val generals = (1L..12L).map {
            general(it, day = (it % 4).toInt(), activity = ActivityCode.entries[(it % 5).toInt()])
        }
        val candidates = generals + (101L..105L).map { event(it, day = (it % 3).toInt()) }

        val straight = rules.select(candidates)

        repeat(5) { seed ->
            assertThat(rules.select(candidates.shuffled(Random(seed)))).isEqualTo(straight)
        }
    }

    // --- the general bundles of one activity (10-5) ---

    @Test
    fun `only the three most recent general bundles of an activity are shown`() {
        val candidates = (1L..5L).map { general(it, day = it.toInt()) }

        assertThat(rules.select(candidates)).containsExactly(5L, 4L, 3L)
    }

    @Test
    fun `an activity with three bundles or fewer shows them all`() {
        val candidates = (1L..3L).map { general(it, day = it.toInt()) }

        assertThat(rules.select(candidates)).containsExactly(3L, 2L, 1L)
    }

    @Test
    fun `the bundles with a group photo are chosen first, then the more recent`() {
        val candidates = listOf(
            general(1, day = 1, group = true),
            general(2, day = 5),
            general(3, day = 4),
            general(4, day = 3),
            general(5, day = 2, group = true)
        )

        // The two with a group photo are chosen (days 1 and 2), then the most recent of the others (day 5).
        assertThat(rules.select(candidates)).containsExactly(2L, 5L, 1L).doesNotContain(3L, 4L)
    }

    @Test
    fun `the group photo only decides which three are chosen, the list is still in the order of the days`() {
        val candidates = listOf(
            general(1, day = 1, group = true),
            general(2, day = 8),
            general(3, day = 7),
            general(4, day = 6)
        )

        assertThat(rules.select(candidates)).containsExactly(2L, 3L, 1L)
    }

    @Test
    fun `each activity has its own three`() {
        val candidates = (1L..4L).map { general(it, day = it.toInt(), activity = ActivityCode.MEAL) } +
            (11L..14L).map { general(it, day = (it - 10).toInt(), activity = ActivityCode.CAFE) }

        val shown = rules.select(candidates)

        assertThat(shown).hasSize(6)
        assertThat(shown).containsExactlyInAnyOrder(4L, 3L, 2L, 14L, 13L, 12L)
    }

    @Test
    fun `the bundles with no activity are counted together`() {
        val candidates = (1L..5L).map { general(it, day = it.toInt(), activity = null) }

        assertThat(rules.select(candidates)).containsExactly(5L, 4L, 3L)
    }

    @Test
    fun `an event is never cut by the limit of an activity`() {
        val candidates = (1L..6L).map { event(it, day = it.toInt()) }

        assertThat(rules.select(candidates)).containsExactly(6L, 5L, 4L, 3L, 2L, 1L)
    }

    // --- the size of the list (10-5) ---

    @Test
    fun `the list has at most 20 bundles, the most recent`() {
        val candidates = (1L..25L).map { event(it, day = it.toInt()) }

        val shown = rules.select(candidates)

        assertThat(shown).hasSize(20)
        assertThat(shown.first()).isEqualTo(25L)
        assertThat(shown.last()).isEqualTo(6L)
    }

    @Test
    fun `exactly 20 bundles are all shown`() {
        val candidates = (1L..20L).map { event(it, day = it.toInt()) }

        assertThat(rules.select(candidates)).hasSize(20)
    }

    @Test
    fun `a general bundle that is more recent is listed before the events, the oldest one is left out`() {
        val candidates = (1L..20L).map { event(it, day = it.toInt()) } + general(100, day = 30)

        val shown = rules.select(candidates)

        assertThat(shown).hasSize(20)
        assertThat(shown.first()).isEqualTo(100L)
        assertThat(shown).doesNotContain(1L)
    }

    @Test
    fun `the limit of an activity is applied before the size of the list`() {
        val candidates = (1L..18L).map { event(it, day = it.toInt()) } +
            (101L..105L).map { general(it, day = it.toInt()) }

        val shown = rules.select(candidates)

        // 18 events and 3 of the 5 general bundles are 21, and the list keeps 20, so the least recent one is left out.
        assertThat(shown).hasSize(20)
        assertThat(shown.take(3)).containsExactly(105L, 104L, 103L)
        assertThat(shown).doesNotContain(1L, 101L, 102L)
    }

    @Test
    fun `no bundle gives an empty list`() {
        assertThat(rules.select(emptyList())).isEmpty()
    }

    // --- the settings ---

    @Test
    fun `the size of the list and the limit of an activity are settings`() {
        val small = RecommendedListRules(CombinationSettings(listMax = 2, sameActivityGeneralMax = 1))
        val candidates = (1L..3L).map { general(it, day = it.toInt(), activity = ActivityCode.MEAL) } +
            (11L..13L).map { general(it, day = it.toInt(), activity = ActivityCode.CAFE) }

        assertThat(small.select(candidates)).containsExactly(13L, 3L)
    }
}
