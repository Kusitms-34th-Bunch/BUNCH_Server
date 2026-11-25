package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CardTestSupport.candidates
import com.bunch.recommendation.combination.CardTestSupport.shot
import com.bunch.recommendation.domain.combination.CardCover
import com.bunch.recommendation.domain.combination.PhotoQuality
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The cover of a card (recommended bundle specification 4, the cover). */
class CardCoverTest {
    private val cover = CardCover()

    @Test
    fun `a photo of good quality in a together moment is the cover`() {
        val card = candidates(
            listOf(
                shot(1, "10:00", quality = PhotoQuality.GOOD),
                shot(2, "10:05", member = 2, quality = PhotoQuality.GOOD),
                shot(3, "10:10", quality = PhotoQuality.NORMAL)
            )
        )

        val result = cover.choose(card, together = setOf(2L, 3L), around = mapOf(1L to 3, 2L to 1, 3L to 2))

        assertThat(result.cover.photoId).isEqualTo(2L)
        assertThat(result.by).isEqualTo(CardCover.TOGETHER_GOOD)
    }

    @Test
    fun `of the photos in a together moment the one with more members around it goes first`() {
        val card = candidates(
            listOf(
                shot(1, "10:00"),
                shot(2, "10:05", member = 2)
            )
        )

        val result = cover.choose(card, together = setOf(1L, 2L), around = mapOf(1L to 2, 2L to 3))

        assertThat(result.cover.photoId).isEqualTo(2L)
    }

    @Test
    fun `without a good photo in a together moment the cover is the good photo with the most members around it`() {
        val card = candidates(
            listOf(
                shot(1, "10:00", quality = PhotoQuality.GOOD),
                shot(2, "10:30", member = 2, quality = PhotoQuality.GOOD),
                shot(3, "11:00", quality = PhotoQuality.NORMAL)
            )
        )

        val result = cover.choose(card, together = setOf(3L), around = mapOf(1L to 1, 2L to 3, 3L to 5))

        assertThat(result.cover.photoId).isEqualTo(2L)
        assertThat(result.by).isEqualTo(CardCover.GOOD_MOST_MEMBERS)
    }

    @Test
    fun `without a good photo the cover is the one with the most members around it`() {
        val card = candidates(
            listOf(
                shot(1, "10:00", quality = PhotoQuality.NORMAL),
                shot(2, "10:30", member = 2, quality = PhotoQuality.NORMAL)
            )
        )

        val result = cover.choose(card, together = emptySet(), around = mapOf(1L to 1, 2L to 2))

        assertThat(result.cover.photoId).isEqualTo(2L)
        assertThat(result.by).isEqualTo(CardCover.MOST_MEMBERS)
    }

    @Test
    fun `when they are as good the earlier photo is the cover, then the lower photo number`() {
        val card = candidates(
            listOf(
                shot(5, "10:00"),
                shot(4, "10:00", member = 2),
                shot(3, "10:30")
            )
        )

        val result = cover.choose(card, together = emptySet(), around = emptyMap())

        assertThat(result.cover.photoId).isEqualTo(4L)
    }
}
