package com.bunch.feed

import com.bunch.feed.service.AdvertisementSelector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Random

class AdvertisementSelectorTest {
    private val selector = AdvertisementSelector()

    @Test
    fun `empty candidates and singleton previous skip without consuming randomness`() {
        val random = ScriptedRandom()
        assertThat(selector.select(emptyList(), 3, null, random)).containsExactly(null, null, null)
        assertThat(selector.select(listOf(7), 3, 7, random)).containsExactly(null, null, null)
        assertThat(selector.select(listOf(7), 3, null, random)).containsExactly(7, null, null)
        assertThat(random.bounds).containsExactly(1)
    }

    @Test
    fun `each remaining eligible index maps to exactly one candidate`() {
        (0..2).forEach { index ->
            val random = ScriptedRandom(index)
            assertThat(selector.select(listOf(10, 20, 30, 40), 1, 20, random))
                .containsExactly(listOf(10L, 30L, 40L)[index])
            assertThat(random.bounds).containsExactly(3)
        }
    }

    @Test
    fun `response has no replacement until exhaustion and refills excluding previous`() {
        val random = ScriptedRandom(0, 0, 0, 0, 1, 0)
        assertThat(selector.select(listOf(1, 2, 3), 6, 1, random))
            .containsExactly(2, 1, 3, 1, 3, 2)
        assertThat(random.bounds).containsExactly(2, 2, 1, 2, 2, 1)
    }

    @Test
    fun `same request invokes fresh randomness instead of replaying a cached sequence`() {
        val random = ScriptedRandom(0, 1)
        assertThat(selector.select(listOf(1, 2), 1, null, random)).containsExactly(1)
        assertThat(selector.select(listOf(1, 2), 1, null, random)).containsExactly(2)
        assertThat(selector.select(listOf(1, 2), 0, null, random)).isEmpty()
        assertThat(random.bounds).containsExactly(2, 2)
    }

    @Test
    fun `exhaustively enumerated branch choices produce every eligible permutation once`() {
        val permutations = (0..1).flatMap { first ->
            (0..1).map { second ->
                selector.select(listOf(1, 2, 3), 3, 1, ScriptedRandom(first, second, 0))
            }
        }
        assertThat(permutations).containsExactlyInAnyOrder(
            listOf(2L, 1L, 3L), listOf(2L, 3L, 1L),
            listOf(3L, 1L, 2L), listOf(3L, 2L, 1L)
        )
    }

    private class ScriptedRandom(vararg indices: Int) : Random(0) {
        private val values = indices.iterator()
        val bounds = mutableListOf<Int>()

        override fun nextInt(bound: Int): Int {
            bounds.add(bound)
            val value = if (values.hasNext()) values.nextInt() else 0
            check(value in 0 until bound)
            return value
        }
    }
}
