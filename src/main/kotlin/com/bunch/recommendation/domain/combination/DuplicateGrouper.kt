package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * Puts the photos of one day that show the same picture into groups (recommended bundle specification 5-4). Two photos
 * belong together when they are
 * - a burst: at most [burstMaxGap] apart on the absolute clock and as alike as [burstMinSimilarity], or
 * - the same composition again: at most [sameCompositionMaxGap] apart and as alike as [sameCompositionMinSimilarity],
 *   whoever took them.
 *
 * Groups are joined as far as the chain goes: A with B and B with C make one group of A, B and C. A photo of which the
 * alikeness is not known (it has no fingerprint) is never joined; it is a group of its own, so is a photo without a
 * time. The same photo uploaded by several members is already one photo (3-1).
 */
class DuplicateGrouper(
    private val times: TimeNormalizer,
    private val similarity: ImageSimilarity,
    private val burstMaxGap: Duration,
    private val burstMinSimilarity: Double,
    private val sameCompositionMaxGap: Duration,
    private val sameCompositionMinSimilarity: Double
) {
    fun group(photos: List<TimedPhoto>): List<DuplicateGroup> {
        val ordered = photos.sortedWith(
            PhotoOrder.byTimeThenId<TimedPhoto>({ times.instant(it) }, { it.photoId })
        )
        val parent = IntArray(ordered.size) { it }
        join(ordered, parent)
        return ordered.indices.groupBy { find(parent, it) }.values
            .map { members -> groupOf(members.map { ordered[it] }) }
    }

    // The photos are in the order of the time, so a photo is only compared with the ones that follow it closely.
    private fun join(ordered: List<TimedPhoto>, parent: IntArray) {
        val widest = maxOf(burstMaxGap, sameCompositionMaxGap)
        for (first in ordered.indices) {
            val at = times.instant(ordered[first]) ?: continue
            for (second in first + 1 until ordered.size) {
                val later = times.instant(ordered[second]) ?: break
                val gap = Duration.between(at, later)
                if (gap > widest) break
                if (alike(ordered[first], ordered[second], gap)) parent[find(parent, second)] = find(parent, first)
            }
        }
    }

    private fun alike(first: TimedPhoto, second: TimedPhoto, gap: Duration): Boolean {
        val likeness = similarity.similarity(first.photo, second.photo) ?: return false
        return (gap <= burstMaxGap && likeness >= burstMinSimilarity) ||
            (gap <= sameCompositionMaxGap && likeness >= sameCompositionMinSimilarity)
    }

    private fun groupOf(members: List<TimedPhoto>): DuplicateGroup = DuplicateGroup(
        id = members.minOf { it.photoId },
        photoIds = members.map { it.photoId },
        uploaderIds = members.flatMap { photo -> photo.photo.uploads.map { it.memberId } }.toSet()
    )

    private fun find(parent: IntArray, index: Int): Int {
        var root = index
        while (parent[root] != root) root = parent[root]
        var node = index
        while (parent[node] != root) {
            val next = parent[node]
            parent[node] = root
            node = next
        }
        return root
    }
}
