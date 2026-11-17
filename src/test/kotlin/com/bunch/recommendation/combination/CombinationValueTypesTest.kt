package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.Embedding
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventResult
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.domain.combination.TimeRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class CombinationValueTypesTest {
    private val nine = Instant.parse("2026-10-19T09:00:00Z")
    private val ten = Instant.parse("2026-10-19T10:00:00Z")
    private val eleven = Instant.parse("2026-10-19T11:00:00Z")
    private val twelve = Instant.parse("2026-10-19T12:00:00Z")

    @Test
    fun `a confidence is from 0 to 1`() {
        assertThat(Reading("a", 0.0).confidence).isEqualTo(0.0)
        assertThat(Reading("a", 1.0).confidence).isEqualTo(1.0)
        assertThatThrownBy { Reading("a", 1.01) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Reading("a", -0.01) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `two embeddings are equal when all their numbers are`() {
        val first = Embedding(floatArrayOf(0.6f, 0.8f))

        assertThat(first).isEqualTo(Embedding(floatArrayOf(0.6f, 0.8f)))
        assertThat(first.hashCode()).isEqualTo(Embedding(floatArrayOf(0.6f, 0.8f)).hashCode())
        assertThat(first).isNotEqualTo(Embedding(floatArrayOf(0.8f, 0.6f)))
        assertThatThrownBy { Embedding(floatArrayOf()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a time range does not end before it starts`() {
        assertThat(TimeRange(nine, nine).start).isEqualTo(nine)
        assertThat(TimeRange(nine, ten).end).isEqualTo(ten)
        assertThatThrownBy { TimeRange(ten, nine) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the range of an event holds its core`() {
        val core = TimeRange(ten, eleven)

        assertThat(confirmed(core, TimeRange(nine, twelve)).range.start).isEqualTo(nine)
        assertThat(confirmed(core, TimeRange(ten, eleven)).range).isEqualTo(core)
        assertThatThrownBy { confirmed(core, TimeRange(ten, ten)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { confirmed(core, TimeRange(eleven, twelve)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an event that failed keeps its clue photos`() {
        val result = EventResult(
            confirmed = emptyList(),
            failed = listOf(EventResult.Failed(EventType.BIRTHDAY, cluePhotoIds = listOf(4L, 5L)))
        )

        assertThat(result.failed.single().cluePhotoIds).containsExactly(4L, 5L)
    }

    private fun confirmed(core: TimeRange, range: TimeRange) = EventResult.Confirmed(
        type = EventType.BIRTHDAY,
        establishedBy = EstablishedBy.STRONG,
        core = core,
        range = range,
        cluePhotoIds = listOf(1L),
        relatedPhotoIds = listOf(1L, 2L)
    )
}
