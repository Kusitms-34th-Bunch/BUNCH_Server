package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CardTestSupport.shot
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.PreparationTestSupport.scene
import com.bunch.recommendation.domain.combination.CardCandidate
import com.bunch.recommendation.domain.combination.CardScorer
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test

/** The score of a picture: its quality and how little it looks like the ones that are chosen (specification 8-4). */
class CardScorerTest {
    private val scorer = CardScorer()

    private fun candidates(vararg photos: RecoPhoto): List<CardCandidate> = ready(photos.toList()).photos.map {
        CardCandidate(
            photo = it,
            shareMember = it.photo.uploads.first().memberId,
            clue = false,
            person = false,
            group = false,
            subject = null
        )
    }

    @Test
    fun `a good picture scores 0_1 more than a normal one, with nothing chosen`() {
        val (good, normal) = candidates(
            shot(1, "10:00", quality = PhotoQuality.GOOD),
            shot(2, "10:05", quality = PhotoQuality.NORMAL)
        )

        assertThat(scorer.score(good, emptyList())).isEqualTo(0.6, offset(1e-9))
        assertThat(scorer.score(normal, emptyList())).isEqualTo(0.5, offset(1e-9))
    }

    @Test
    fun `a picture that is the same as a chosen one has no diversity, one that is not alike has all of it`() {
        val (same, other, chosen) = candidates(
            shot(1, "10:00", fingerprint = scene(0)),
            shot(2, "10:05", fingerprint = scene(1)),
            shot(3, "10:10", fingerprint = scene(0))
        )

        val likeness = scorer.score(same, listOf(chosen))
        val unlike = scorer.score(other, listOf(chosen))

        assertThat(likeness).isEqualTo(0.2, offset(1e-9))
        assertThat(unlike).isGreaterThan(likeness)
    }

    @Test
    fun `the diversity of a picture is measured against the chosen picture that is the most alike`() {
        val (candidate, far, near) = candidates(
            shot(1, "10:00", fingerprint = scene(0)),
            shot(2, "10:05", fingerprint = scene(1)),
            shot(3, "10:10", fingerprint = scene(0, bitsApart = 3))
        )

        val withFar = scorer.score(candidate, listOf(far))
        val withBoth = scorer.score(candidate, listOf(far, near))

        assertThat(withBoth).isLessThan(withFar)
    }

    @Test
    fun `a picture that cannot be compared counts as not alike`() {
        val (candidate, chosen) = candidates(shot(1, "10:00"), shot(2, "10:05", fingerprint = scene(0)))

        assertThat(scorer.score(candidate, listOf(chosen))).isEqualTo(0.6, offset(1e-9))
    }
}
