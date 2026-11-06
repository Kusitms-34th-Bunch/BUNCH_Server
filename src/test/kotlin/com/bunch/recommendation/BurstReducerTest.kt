package com.bunch.recommendation

import com.bunch.recommendation.domain.BurstReducer
import com.bunch.recommendation.domain.PhotoSample
import com.bunch.recommendation.domain.RecommendationSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

class BurstReducerTest {
    private val reducer = BurstReducer()
    private val day = LocalDate.of(2026, 10, 19)
    private var nextId = 1L

    @Test
    fun `near identical shots of one member are reduced to one`() {
        val first = shot("10:00:00")
        val second = shot("10:00:02")

        val kept = reducer.reduce(listOf(first, second))

        assertThat(ids(kept)).containsExactly(first.photoId)
    }

    @Test
    fun `a burst of three keeps the middle shot`() {
        val first = shot("10:00:00")
        val middle = shot("10:00:01")
        val last = shot("10:00:02")

        assertThat(ids(reducer.reduce(listOf(first, middle, last)))).containsExactly(middle.photoId)
    }

    @Test
    fun `shots further apart than three seconds stay`() {
        val first = shot("10:00:00")
        val second = shot("10:00:04")

        assertThat(reducer.reduce(listOf(first, second))).hasSize(2)
    }

    @Test
    fun `shots exactly three seconds apart are one burst`() {
        val first = shot("10:00:00")
        val second = shot("10:00:03")

        assertThat(reducer.reduce(listOf(first, second))).hasSize(1)
    }

    @Test
    fun `shots that differ in at most five bits are alike`() {
        val base = shot("10:00:00", fingerprint = 0L)

        assertThat(reducer.reduce(listOf(base, shot("10:00:01", fingerprint = 0b11111L)))).hasSize(1)
        assertThat(reducer.reduce(listOf(base, shot("10:00:01", fingerprint = 0b111111L)))).hasSize(2)
    }

    @Test
    fun `shots of different members stay even when they look the same`() {
        val first = shot("10:00:00", uploaders = setOf(1L))
        val second = shot("10:00:01", uploaders = setOf(2L))

        assertThat(reducer.reduce(listOf(first, second))).hasSize(2)
    }

    @Test
    fun `a photo uploaded together is not mixed with the shots of one of its uploaders`() {
        val together = shot("10:00:00", uploaders = setOf(1L, 2L))
        val alone = shot("10:00:01", uploaders = setOf(1L))

        assertThat(reducer.reduce(listOf(together, alone))).hasSize(2)
    }

    @Test
    fun `photos without a fingerprint are never reduced`() {
        val first = shot("10:00:00", fingerprint = null)
        val second = shot("10:00:01", fingerprint = null)

        assertThat(reducer.reduce(listOf(first, second))).hasSize(2)
    }

    @Test
    fun `photos without a time are never reduced`() {
        val first = shot(null)
        val second = shot(null)

        assertThat(reducer.reduce(listOf(first, second))).hasSize(2)
    }

    @Test
    fun `a chain of shots is one burst even when the ends are far apart`() {
        val shots = listOf("10:00:00", "10:00:02", "10:00:04", "10:00:06").map { shot(it) }

        assertThat(ids(reducer.reduce(shots))).containsExactly(shots[1].photoId)
    }

    @Test
    fun `a photo of another member in between does not break the burst`() {
        val first = shot("10:00:00", uploaders = setOf(1L))
        val other = shot("10:00:01", uploaders = setOf(2L))
        val second = shot("10:00:02", uploaders = setOf(1L))

        val kept = reducer.reduce(listOf(first, other, second))

        assertThat(ids(kept)).containsExactlyInAnyOrder(first.photoId, other.photoId)
    }

    @Test
    fun `the settings change the gap`() {
        val first = shot("10:00:00")
        val second = shot("10:00:04")
        val wider = BurstReducer(RecommendationSettings(burstGap = Duration.ofSeconds(5)))

        assertThat(wider.reduce(listOf(first, second))).hasSize(1)
        assertThat(reducer.reduce(listOf(first, second))).hasSize(2)
    }

    @Test
    fun `the order of the input is kept`() {
        val first = shot("10:00:00")
        val lone = shot("12:00:00", uploaders = setOf(2L))
        val second = shot("10:00:01")

        val kept = reducer.reduce(listOf(lone, second, first))

        assertThat(ids(kept)).containsExactly(lone.photoId, first.photoId)
    }

    @Test
    fun `no photos give no photos`() {
        assertThat(reducer.reduce(emptyList())).isEmpty()
    }

    private fun shot(
        time: String?,
        uploaders: Set<Long> = setOf(1L),
        fingerprint: Long? = 0L
    ) = PhotoSample(nextId++, day, time?.let(LocalTime::parse), uploaders, fingerprint = fingerprint)

    private fun ids(photos: List<PhotoSample>): List<Long> = photos.map { it.photoId }
}
