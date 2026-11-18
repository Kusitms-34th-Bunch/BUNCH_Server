package com.bunch.recommendation.domain.combination

import java.time.Duration
import java.time.LocalDate

/**
 * Moves the times of a member whose device clock is wrong (recommended bundle specification 5-5). The photos a member
 * took on a day are compared with the photos of the same scene that the other members took the same day: the time
 * differences are collected, and when there are at least [minPairs] of them and their median is at least [minMedian]
 * from zero, the member's photos of that day are moved by the median. With nothing to compare nothing is moved. What
 * is moved is only for the calculation (the photo is not changed).
 *
 * The specification takes the pairs from the photos "of the same duplicate group" (5-4), but a duplicate group needs
 * the times to be close, and a clock that is off by ten minutes or more can never give that, so nothing could be
 * corrected.
 * The planner's text asks for "the photos of the same scene of the other members and the difference in time" (X5), so
 * the pairs are found without a limit of time: a photo of the member is paired with the photo of another member that
 * is the most alike, when it is as alike as the same composition needs ([minSimilarity]). Only a photo that one member
 * alone uploaded counts as the member's own: a photo several members uploaded was taken by the clock of one of them,
 * and which one is not known. The member's own photos of the day are moved, and those of the other days are not.
 *
 * The specification does not say whose clock is wrong when two members differ from each other. This corrects the one
 * who differs most from the rest, one at a time, and the others are compared again with the corrected times. When two
 * differ equally (two members only), it is the member with fewer photos that day, then the one with the higher member
 * ID: nothing says who is right, and the member who came first is taken as the reference.
 */
class ClockCorrector(
    private val times: TimeNormalizer,
    private val similarity: ImageSimilarity,
    private val minSimilarity: Double,
    private val minPairs: Int,
    private val minMedian: Duration
) {
    fun correct(photos: List<TimedPhoto>): List<TimedPhoto> {
        var current = photos
        val corrected = mutableSetOf<Pair<Long, LocalDate>>()
        while (true) {
            val next = candidates(current)
                .filter { it.key !in corrected }
                .sortedWith(
                    compareByDescending<Candidate> { it.median.abs() }
                        .thenBy { it.photoCount }
                        .thenByDescending { it.key.first }
                        .thenBy { it.key.second }
                )
                .firstOrNull() ?: return current
            corrected += next.key
            current = current.map { photo ->
                if (isOwnPhotoOf(photo, next.key)) photo.copy(shift = photo.shift.minus(next.median)) else photo
            }
        }
    }

    // A member and a day that has enough pairs that are far enough apart to be moved.
    private fun candidates(photos: List<TimedPhoto>): List<Candidate> {
        val days = photos.filter { it.hasTime }.groupBy { times.dayKey(it)!! }
        return days.flatMap { (day, dayPhotos) ->
            dayPhotos.filter { it.photo.uploads.size == 1 }
                .groupBy { it.photo.uploads.single().memberId }
                .mapNotNull { (member, own) -> candidate(member, day, own, dayPhotos) }
        }
    }

    private fun candidate(
        member: Long,
        day: LocalDate,
        own: List<TimedPhoto>,
        dayPhotos: List<TimedPhoto>
    ): Candidate? {
        val others = dayPhotos.filter { other -> other.photo.uploads.none { it.memberId == member } }
        val differences = own.mapNotNull { photo -> bestMatch(photo, others)?.let { difference(photo, it) } }
        if (differences.size < minPairs) return null
        val median = median(differences)
        if (median.abs() < minMedian) return null
        return Candidate(member to day, median, own.size)
    }

    private fun bestMatch(photo: TimedPhoto, others: List<TimedPhoto>): TimedPhoto? = others
        .mapNotNull { other ->
            similarity.similarity(photo.photo, other.photo)?.takeIf { it >= minSimilarity }?.let { other to it }
        }
        .sortedWith(compareByDescending<Pair<TimedPhoto, Double>> { it.second }.thenBy { it.first.photoId })
        .firstOrNull()?.first

    // How much later the member's clock shows the photo than the clock of the other member does.
    private fun difference(photo: TimedPhoto, other: TimedPhoto): Duration =
        Duration.between(times.instant(other), times.instant(photo))

    // The middle of the differences; of an even number, the mean of the two in the middle.
    private fun median(differences: List<Duration>): Duration {
        val sorted = differences.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1].plus(sorted[middle]).dividedBy(2)
    }

    private fun isOwnPhotoOf(photo: TimedPhoto, key: Pair<Long, LocalDate>): Boolean =
        photo.hasTime && photo.photo.uploads.size == 1 && photo.photo.uploads.single().memberId == key.first &&
            times.dayKey(photo) == key.second

    private data class Candidate(
        val key: Pair<Long, LocalDate>,
        val median: Duration,
        val photoCount: Int
    )
}
