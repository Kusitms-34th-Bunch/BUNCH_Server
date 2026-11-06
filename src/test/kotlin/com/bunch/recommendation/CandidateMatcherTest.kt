package com.bunch.recommendation

import com.bunch.recommendation.domain.CandidateMatch
import com.bunch.recommendation.domain.CandidateMatcher
import com.bunch.recommendation.domain.PhotoCluster
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CandidateMatcherTest {
    private val matcher = CandidateMatcher()

    @Test
    fun `a candidate whose photos did not change is kept`() {
        val now = cluster(1, 2, 3, 4, 5, 6)

        val result = match(mapOf(10L to photos(1, 2, 3, 4, 5, 6)), listOf(now))

        assertThat(result).isEqualTo(outcome(kept = mapOf(10L to now)))
    }

    @Test
    fun `a candidate that got new photos is kept as the bigger cluster`() {
        val bigger = cluster(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)

        val result = match(mapOf(10L to photos(1, 2, 3, 4, 5, 6, 7, 8, 9)), listOf(bigger))

        assertThat(result).isEqualTo(outcome(kept = mapOf(10L to bigger)))
    }

    @Test
    fun `a candidate that lost photos is kept while some of its photos remain`() {
        val smaller = cluster(1, 2, 3, 4, 5, 6)

        val result = match(mapOf(10L to photos(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)), listOf(smaller))

        assertThat(result).isEqualTo(outcome(kept = mapOf(10L to smaller)))
    }

    @Test
    fun `one photo left in common is enough to keep a candidate`() {
        val now = cluster(9, 20, 21, 22, 23, 24)

        val result = match(mapOf(10L to photos(1, 2, 3, 4, 5, 9)), listOf(now))

        assertThat(result).isEqualTo(outcome(kept = mapOf(10L to now)))
    }

    @Test
    fun `a photo that only a burst reduction left out still counts as a photo of the candidate`() {
        val now = cluster(1, 2, 3, 4, 5, 6)

        val result = match(mapOf(10L to photos(6, 90, 91)), listOf(now))

        assertThat(result).isEqualTo(outcome(kept = mapOf(10L to now)))
    }

    @Test
    fun `a candidate that grows into a cluster that is not stored is kept`() {
        val now = cluster(1, 2, 3, 4, 5, 6, 40, 41, 42)

        val result = match(mapOf(10L to photos(1, 2, 3, 4, 5, 6)), listOf(now))

        assertThat(result).isEqualTo(outcome(kept = mapOf(10L to now)))
    }

    @Test
    fun `a candidate split into two recommended clusters ends and both become new candidates`() {
        val afternoon = cluster(1, 2, 3, 4, 5, 6)
        val evening = cluster(7, 8, 9, 10, 11, 12)

        val result = match(mapOf(10L to photos(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)), listOf(afternoon, evening))

        assertThat(result).isEqualTo(outcome(created = listOf(afternoon, evening), deleted = setOf(10L)))
    }

    @Test
    fun `when only one part of a split is recommended only that part becomes a new candidate`() {
        val big = cluster(1, 2, 3, 4, 5, 6, 7)
        val small = cluster(8, 9, 10)

        val result = match(
            existing = mapOf(10L to photos(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)),
            clusters = listOf(big, small),
            notRecommended = setOf(small)
        )

        assertThat(result).isEqualTo(outcome(created = listOf(big), deleted = setOf(10L)))
    }

    @Test
    fun `when no part of a split is recommended the candidate just ends`() {
        val first = cluster(1, 2, 3)
        val second = cluster(4, 5, 6)

        val result = match(
            existing = mapOf(10L to photos(1, 2, 3, 4, 5, 6)),
            clusters = listOf(first, second),
            notRecommended = setOf(first, second)
        )

        assertThat(result).isEqualTo(outcome(deleted = setOf(10L)))
    }

    @Test
    fun `two candidates merged into one cluster end and the cluster becomes one new candidate`() {
        val merged = cluster(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)

        val result = match(
            existing = mapOf(10L to photos(1, 2, 3, 4, 5, 6), 11L to photos(8, 9, 10, 11, 12, 13)),
            clusters = listOf(merged)
        )

        assertThat(result).isEqualTo(outcome(created = listOf(merged), deleted = setOf(10L, 11L)))
    }

    @Test
    fun `candidates merged into a cluster that is not recommended just end`() {
        val merged = cluster(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)

        val result = match(
            existing = mapOf(10L to photos(1, 2, 3, 4, 5, 6), 11L to photos(7, 8, 9, 10, 11, 12)),
            clusters = listOf(merged),
            notRecommended = setOf(merged)
        )

        assertThat(result).isEqualTo(outcome(deleted = setOf(10L, 11L)))
    }

    @Test
    fun `a split and a merge at the same time end every candidate involved`() {
        val left = cluster(1, 2, 3, 4, 5, 6)
        val right = cluster(7, 8, 9, 10, 11, 12, 13, 14, 15)

        val result = match(
            existing = mapOf(10L to photos(1, 2, 3, 4, 5, 6, 7, 8), 11L to photos(9, 10, 11, 12, 13, 14, 15)),
            clusters = listOf(left, right)
        )

        assertThat(result).isEqualTo(outcome(created = listOf(left, right), deleted = setOf(10L, 11L)))
    }

    @Test
    fun `a candidate whose cluster is no longer recommended ends`() {
        val now = cluster(1, 2, 3, 4, 5)

        val result = match(
            existing = mapOf(10L to photos(1, 2, 3, 4, 5, 6)),
            clusters = listOf(now),
            notRecommended = setOf(now)
        )

        assertThat(result).isEqualTo(outcome(deleted = setOf(10L)))
    }

    @Test
    fun `a candidate with no photo in any cluster ends`() {
        val other = cluster(20, 21, 22, 23, 24, 25)

        val result = match(mapOf(10L to photos(1, 2, 3, 4, 5, 6)), listOf(other))

        assertThat(result).isEqualTo(outcome(created = listOf(other), deleted = setOf(10L)))
    }

    @Test
    fun `a recommended cluster without a candidate becomes a new candidate and another one does not`() {
        val recommended = cluster(1, 2, 3, 4, 5, 6)
        val small = cluster(7, 8)

        val result = match(emptyMap(), listOf(recommended, small), notRecommended = setOf(small))

        assertThat(result).isEqualTo(outcome(created = listOf(recommended)))
    }

    @Test
    fun `candidates that are not involved with each other are decided one by one`() {
        val stays = cluster(1, 2, 3, 4, 5, 6)
        val splitA = cluster(10, 11, 12, 13, 14, 15)
        val splitB = cluster(16, 17, 18, 19, 20, 21)
        val brandNew = cluster(30, 31, 32, 33, 34, 35)

        val result = match(
            existing = mapOf(
                1L to photos(1, 2, 3, 4, 5, 6),
                2L to photos(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21),
                3L to photos(90, 91, 92, 93, 94, 95)
            ),
            clusters = listOf(stays, splitA, splitB, brandNew)
        )

        assertThat(result).isEqualTo(
            outcome(
                kept = mapOf(1L to stays),
                created = listOf(splitA, splitB, brandNew),
                deleted = setOf(2L, 3L)
            )
        )
    }

    @Test
    fun `nothing stored and nothing grouped changes nothing`() {
        val result = match(emptyMap(), emptyList())

        assertThat(result).isEqualTo(outcome())
    }

    private fun cluster(vararg photoIds: Long) = PhotoCluster(
        photoIds = photoIds.toList(),
        startDate = LocalDate.of(2026, 10, 19),
        endDate = null,
        uploaderIds = setOf(1L, 2L),
        qualifies = true
    )

    private fun photos(vararg photoIds: Long): Set<Long> = photoIds.toSet()

    private fun match(
        existing: Map<Long, Set<Long>>,
        clusters: List<PhotoCluster>,
        notRecommended: Set<PhotoCluster> = emptySet()
    ): CandidateMatch = matcher.match(existing, clusters) { it !in notRecommended }

    private fun outcome(
        kept: Map<Long, PhotoCluster> = emptyMap(),
        created: List<PhotoCluster> = emptyList(),
        deleted: Set<Long> = emptySet()
    ) = CandidateMatch(kept, created, deleted)
}
