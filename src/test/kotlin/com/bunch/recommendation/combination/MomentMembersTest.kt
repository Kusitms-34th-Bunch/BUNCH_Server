package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CardTestSupport.candidates
import com.bunch.recommendation.combination.CardTestSupport.shot
import com.bunch.recommendation.domain.combination.MomentMembers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** How many members took photos around a picture (recommended bundle specification 4, fifth rule). */
class MomentMembersTest {
    private val moments = MomentMembers(Duration.ofMinutes(10))

    @Test
    fun `the members who took a picture within 10 minutes before or after are counted, the picture's own included`() {
        val pool = candidates(
            listOf(
                shot(1, "10:00", member = 1),
                shot(2, "10:08", member = 2),
                shot(3, "10:18", member = 3)
            )
        )

        val result = moments.of(pool)

        assertThat(result).containsEntry(1L, 2).containsEntry(2L, 3).containsEntry(3L, 2)
    }

    @Test
    fun `exactly 10 minutes is still around, and a member counts once however many pictures`() {
        val pool = candidates(
            listOf(
                shot(1, "10:00", member = 1),
                shot(2, "10:10", member = 2),
                shot(3, "10:05", member = 2),
                shot(4, "10:06", member = 2)
            )
        )

        assertThat(moments.of(pool)).containsEntry(1L, 2)
    }

    @Test
    fun `a picture with no time has no moment around it`() {
        val pool = candidates(listOf(shot(1, "10:00"), shot(2, null, member = 2)))

        val result = moments.of(pool)

        assertThat(result).containsEntry(1L, 1).doesNotContainKey(2L)
    }
}
