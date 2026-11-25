package com.bunch.recommendation.domain.combination

import java.time.Duration
import java.time.Instant

/**
 * The moments the members of a bundle took photos together (recommended bundle specification 2-1, the together moment
 * R2). A together moment is a time in which the usable photos of two different members were taken at most [window]
 * apart. It stands in for "the activity is known": a bundle in which the members did not take photos near each other is
 * a pile of what each of them did alone, and it is not recommended (3-4).
 *
 * Only the photos that are given are looked at: a bundle counts the moments of its own photos, and a part that is cut
 * from a bundle counts them again among its own photos. A photo without a time cannot be near another one, and a photo
 * that is not usable ([isUsable]) does not count.
 *
 * A photo that several members uploaded, or a copy of one photo that another member saved and uploaded again (a
 * group of duplicates), is not a sign that those members were together: the others got it from the one who took it.
 * So the group counts as the photos of the member who uploaded it first, by the time of the upload, then the lower
 * member number (specification X9: the same input always gives the same answer). Only the photos of the group that
 * are given are used to find that member.
 *
 * Nothing is stored and nothing is shown: the answer is used to cut and to choose the photos of a bundle (L4).
 */
class TogetherMoments(
    private val window: Duration,
    private val isUsable: (PreparedPhoto) -> Boolean
) {
    /** What was found: the photos that are in a together moment, and the moments, in the order of the time. */
    data class Result(
        val photoIds: Set<Long>,
        val moments: List<TimeRange>
    ) {
        /** Whether the members took photos together at least once. */
        val exists: Boolean
            get() = moments.isNotEmpty()
    }

    fun find(photos: Collection<PreparedPhoto>, groups: List<DuplicateGroup>): Result {
        val entries = entriesOf(photos, groups)
        val photoIds = mutableSetOf<Long>()
        val stretches = mutableListOf<TimeRange>()
        for (first in entries.indices) {
            for (second in first + 1 until entries.size) {
                if (Duration.between(entries[first].at, entries[second].at) > window) break
                if (entries[first].member == entries[second].member) continue
                photoIds += entries[first].photoId
                photoIds += entries[second].photoId
                stretches += TimeRange(entries[first].at, entries[second].at)
            }
        }
        return Result(photoIds, merged(stretches))
    }

    private data class Entry(val photoId: Long, val at: Instant, val member: Long)

    // The usable photos with a time, each with the member it counts for, in the order of the time, then the number.
    private fun entriesOf(photos: Collection<PreparedPhoto>, groups: List<DuplicateGroup>): List<Entry> {
        val groupOf = groups.flatMap { group -> group.photoIds.map { it to group.id } }.toMap()
        val counted = photos.filter { isUsable(it) && it.instant != null }
        val firstUploader = counted
            .groupBy { groupOf[it.photoId] ?: -it.photoId }
            .mapValues { (_, members) -> firstUploaderOf(members) }
        return counted
            .map { Entry(it.photoId, it.instant!!, firstUploader.getValue(groupOf[it.photoId] ?: -it.photoId)) }
            .sortedWith(compareBy({ it.at }, { it.photoId }))
    }

    // The member who uploaded any photo of the group first; the lower member number when they did it at the same time.
    private fun firstUploaderOf(group: List<PreparedPhoto>): Long =
        group.flatMap { it.photo.uploads }
            .minWith(compareBy({ it.uploadedAt }, { it.memberId }))
            .memberId

    // The stretches that touch or overlap are one moment.
    private fun merged(stretches: List<TimeRange>): List<TimeRange> {
        val moments = mutableListOf<TimeRange>()
        for (stretch in stretches.sortedWith(compareBy({ it.start }, { it.end }))) {
            val last = moments.lastOrNull()
            if (last != null && !stretch.start.isAfter(last.end)) {
                if (stretch.end.isAfter(last.end)) moments[moments.lastIndex] = TimeRange(last.start, stretch.end)
            } else {
                moments += stretch
            }
        }
        return moments
    }
}
