package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.PhotoOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** The order of recommended bundle specification 1.8: the earlier time, then the lower photo ID. */
class PhotoOrderTest {
    private data class Item(val id: Long, val time: Instant?)

    private val order = PhotoOrder.byTimeThenId<Item>({ it.time }, { it.id })
    private val nine = Instant.parse("2026-10-19T09:00:00Z")
    private val ten = Instant.parse("2026-10-19T10:00:00Z")

    @Test
    fun `the earlier time comes first whatever the ID`() {
        val sorted = listOf(Item(1L, ten), Item(2L, nine)).sortedWith(order)

        assertThat(sorted.map { it.id }).containsExactly(2L, 1L)
    }

    @Test
    fun `at the same time the lower photo ID comes first`() {
        val sorted = listOf(Item(7L, nine), Item(3L, nine), Item(5L, nine)).sortedWith(order)

        assertThat(sorted.map { it.id }).containsExactly(3L, 5L, 7L)
    }

    @Test
    fun `a photo without a time comes after all that have one, by photo ID`() {
        val sorted = listOf(Item(1L, null), Item(9L, ten), Item(0L, null), Item(4L, nine)).sortedWith(order)

        assertThat(sorted.map { it.id }).containsExactly(4L, 9L, 0L, 1L)
    }

    @Test
    fun `the same photos give the same order however they come in`() {
        val items = listOf(Item(5L, nine), Item(2L, nine), Item(8L, null), Item(1L, ten), Item(3L, null))

        val expected = items.sortedWith(order).map { it.id }

        assertThat(items.reversed().sortedWith(order).map { it.id }).isEqualTo(expected)
        assertThat(items.shuffled(java.util.Random(7)).sortedWith(order).map { it.id }).isEqualTo(expected)
    }
}
