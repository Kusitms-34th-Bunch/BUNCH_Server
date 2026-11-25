package com.bunch.recommendation.domain.combination

import java.time.Duration
import java.time.Instant

/**
 * Puts the photos of one day that are the same shot into groups (recommended bundle specification 2-1, the duplicate
 * group R1). Two photos belong together when one of these holds:
 * - they are the same original saved again: the shooting time is the same down to the millisecond, and so are the model
 *   of the device and the size of the image. All of them must be known: a time that is known only to the second does
 *   not count, because two members with the same kind of phone often shoot the same group photo in the same second;
 * - they are a burst: the same member uploaded both, the model of the device is the same, and they are at most
 *   [burstMaxGap] apart. A model names a kind of device, not one device, so it only counts for the same member.
 *
 * The same file uploaded by several members is already one photo (3-1), so the file hash needs no step here. The image
 * itself is never looked at: the same scene shot by other members, or shot again by the same member after more than
 * [burstMaxGap], is not a duplicate; the choice of the card keeps such photos apart by their times (4).
 *
 * Groups are joined as far as the chain goes: A with B and B with C make one group of A, B and C. A value that is not
 * known never makes two photos alike, so a photo without it is a group of its own, and so is a photo without a time.
 * The result is the same whatever the order the photos come in (1.5).
 */
class DuplicateGrouper(
    private val times: TimeNormalizer,
    private val burstMaxGap: Duration
) {
    fun group(photos: List<TimedPhoto>): List<DuplicateGroup> {
        val ordered = photos.sortedWith(PhotoOrder.byTimeThenId<TimedPhoto>({ moment(it) }, { it.photoId }))
        val parent = IntArray(ordered.size) { it }
        join(ordered, parent)
        return ordered.indices.groupBy { find(parent, it) }.values
            .map { members -> groupOf(members.map { ordered[it] }) }
    }

    private fun join(ordered: List<TimedPhoto>, parent: IntArray) {
        for (first in ordered.indices) {
            for (second in first + 1 until ordered.size) {
                if (sameOriginal(ordered[first], ordered[second]) || burst(ordered[first], ordered[second])) {
                    parent[find(parent, second)] = find(parent, first)
                }
            }
        }
    }

    // The same moment to the millisecond, from a device of the same model, and an image of the same size.
    private fun sameOriginal(first: TimedPhoto, second: TimedPhoto): Boolean {
        val a = first.photo
        val b = second.photo
        val firstMoment = times.exactInstant(a) ?: return false
        val secondMoment = times.exactInstant(b) ?: return false
        val model = a.deviceModel ?: return false
        val width = a.width ?: return false
        val height = a.height ?: return false
        return firstMoment == secondMoment && model == b.deviceModel && width == b.width && height == b.height
    }

    // The same member, the same model of device, and close together in time.
    private fun burst(first: TimedPhoto, second: TimedPhoto): Boolean {
        val model = first.photo.deviceModel ?: return false
        if (model != second.photo.deviceModel) return false
        val shared = first.photo.uploads.any { upload -> second.photo.uploads.any { it.memberId == upload.memberId } }
        if (!shared) return false
        val at = moment(first) ?: return false
        val later = moment(second) ?: return false
        return Duration.between(at, later).abs() <= burstMaxGap
    }

    // The shooting time on the absolute clock, with the milliseconds when the photo has its own time and tells them.
    private fun moment(photo: TimedPhoto): Instant? {
        val second = times.instant(photo) ?: return null
        val own = photo.photo
        val millis = if (photo.time == own.capturedTime) own.capturedMillis else null
        return second.plusMillis((millis ?: 0).toLong())
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
