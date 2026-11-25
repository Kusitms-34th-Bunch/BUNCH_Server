package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.ListCandidate
import com.bunch.recommendation.domain.combination.RecommendedListRules
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/** Which recommended bundles the list shows and in what order (photo box 4.2.9 rule 6, specification X8, D2). */
class RecommendedListRulesTest {
    private val rules = RecommendedListRules()
    private val base = Instant.parse("2026-10-01T00:00:00Z")

    private fun bundle(id: Long, addedDay: Long = 0, members: Int = 2, photos: Int = 6) = ListCandidate(
        id = id,
        lastPhotoAddedAt = base.plusSeconds(addedDay * 86_400L),
        uploaderCount = members,
        photoCount = photos
    )

    // --- the order (4.2.9 rule 6) ---

    @Test
    fun `the bundle whose last photo was added most recently is first, whatever the day of its photos`() {
        val result = rules.select(listOf(bundle(1, addedDay = 1), bundle(2, addedDay = 9), bundle(3, addedDay = 5)))

        assertThat(result).containsExactly(2L, 3L, 1L)
    }

    @Test
    fun `when the last photo was added at the same time the one with more members on its card comes first`() {
        val result = rules.select(listOf(bundle(1, members = 2), bundle(2, members = 4), bundle(3, members = 3)))

        assertThat(result).containsExactly(2L, 3L, 1L)
    }

    @Test
    fun `when the members are as many the one with more photos on its card comes first`() {
        val result = rules.select(listOf(bundle(1, photos = 6), bundle(2, photos = 10), bundle(3, photos = 8)))

        assertThat(result).containsExactly(2L, 3L, 1L)
    }

    @Test
    fun `the time of the last photo wins over the members, and the members win over the photos`() {
        val result = rules.select(
            listOf(
                bundle(1, addedDay = 3, members = 2, photos = 6),
                bundle(2, addedDay = 3, members = 4, photos = 6),
                bundle(3, addedDay = 4, members = 2, photos = 6),
                bundle(4, addedDay = 3, members = 4, photos = 10)
            )
        )

        assertThat(result).containsExactly(3L, 4L, 2L, 1L)
    }

    @Test
    fun `bundles that cannot be told apart come in the order of their numbers, the greater first`() {
        val result = rules.select(listOf(bundle(1), bundle(3), bundle(2)))

        assertThat(result).containsExactly(3L, 2L, 1L)
    }

    @Test
    fun `the same bundles are listed the same way whatever the order they come in`() {
        val all = (1L..30L).map {
            bundle(it, addedDay = it % 4, members = (it % 3).toInt() + 2, photos = 6 + (it % 3).toInt())
        }

        val straight = rules.select(all)
        val shuffled = rules.select(all.shuffled(Random(11)))

        assertThat(shuffled).containsExactlyElementsOf(straight)
    }

    // --- the limit (X8) and the repeated gatherings (D2) ---

    @Test
    fun `the list has at most the number of the settings, the first ones of the order`() {
        val small = RecommendedListRules(CombinationSettings(listMax = 2))

        val result = small.select(listOf(bundle(1, addedDay = 1), bundle(2, addedDay = 3), bundle(3, addedDay = 2)))

        assertThat(result).containsExactly(2L, 3L)
    }

    @Test
    fun `the list has 20 bundles at most by default`() {
        val result = rules.select((1L..25L).map { bundle(it, addedDay = it) })

        assertThat(result).hasSize(20)
        assertThat(result.first()).isEqualTo(25L)
        assertThat(result.last()).isEqualTo(6L)
    }

    @Test
    fun `bundles that repeat are all shown, there is no limit for the same kind of gathering`() {
        val result = rules.select((1L..10L).map { bundle(it, addedDay = it) })

        assertThat(result).hasSize(10)
    }

    @Test
    fun `no bundle gives an empty list`() {
        assertThat(rules.select(emptyList())).isEmpty()
    }
}
