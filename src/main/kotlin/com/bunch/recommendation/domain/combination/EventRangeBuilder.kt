package com.bunch.recommendation.domain.combination

import java.time.Duration
import java.time.Instant

/**
 * Gives each event the photos of its time (recommended bundle specification 6-3). An event that is not a day event
 * starts from its core and takes in the photo before and after as long as the gap to it is less than the time that
 * keeps the event together (3-5), and stops at the start of another event's core. The photos of all members count for
 * the gaps, the unusable ones too (5-1). A photo of two events goes to the one whose core is nearer in time, and to the
 * earlier one when they are as near (the stretches are widened one by one, so this only happens in the gap between two
 * cores).
 *
 * A day event has the whole day, without what the other events took (a day event is a day, not a stretch of it). A
 * photo with a date only has no time to put in a range, and is left to the step that gives such photos to a bundle
 * (7-9).
 *
 * The specification does not say what two day events of one day do. They share what the others did not take: a photo
 * goes to the event whose core is nearer. The range of a day event is read as the time from its first photo to its
 * last, because a day is a set of photos and not a stretch of time.
 */
class EventRangeBuilder(
    private val settings: CombinationSettings = CombinationSettings()
) {
    /** An event with the photos of its range, in the order of the time, and the range from the first to the last. */
    data class Ranged(
        val candidate: EventCandidate,
        val photos: List<PreparedPhoto>,
        val range: TimeRange
    )

    fun build(day: DayPreparation.Ready, events: List<EventCandidate>): List<Ranged> {
        val timed = day.photos.filter { it.instant != null }
        val owner = mutableMapOf<Long, Int>()

        val widened = events.indices.filter { events[it].type.pace != Pace.DAY }
            .associateWith { index -> widen(events[index], events, timed).map { it.photoId }.toSet() }
        for (photo in timed) {
            val claimants = widened.filterValues { photo.photoId in it }.keys
            nearest(photo, claimants, events)?.let { owner[photo.photoId] = it }
        }

        val dayEvents = events.indices.filter { events[it].type.pace == Pace.DAY }
        for (photo in timed.filter { it.photoId !in owner }) {
            nearest(photo, dayEvents, events)?.let { owner[photo.photoId] = it }
        }

        return events.mapIndexed { index, event ->
            val photos = timed.filter { owner[it.photoId] == index }
            val range = photos.takeIf { it.isNotEmpty() }
                ?.let { TimeRange(it.first().instant!!, it.last().instant!!) } ?: event.core
            Ranged(event, photos, range)
        }
    }

    // The core and the photos before and after it that follow it closely, up to the core of another event.
    private fun widen(
        event: EventCandidate,
        events: List<EventCandidate>,
        timed: List<PreparedPhoto>
    ): List<PreparedPhoto> {
        val together = settings.continuityOf(event.type.pace)!!
        val others = events.filter { it !== event }
        val nextCore = others.map { it.core.start }.filter { !it.isBefore(event.core.end) }.minOrNull()
        val previousCore = others.map { it.core.end }.filter { !it.isAfter(event.core.start) }.maxOrNull()

        val inside = timed.filter { !it.instant!!.isBefore(event.core.start) && !it.instant.isAfter(event.core.end) }
        val after = mutableListOf<PreparedPhoto>()
        var end: Instant = event.core.end
        for (photo in timed.filter { it.instant!!.isAfter(event.core.end) }) {
            val at = photo.instant!!
            if (nextCore != null && !at.isBefore(nextCore)) break
            if (Duration.between(end, at) >= together) break
            after += photo
            end = at
        }
        val before = mutableListOf<PreparedPhoto>()
        var start: Instant = event.core.start
        for (photo in timed.filter { it.instant!!.isBefore(event.core.start) }.reversed()) {
            val at = photo.instant!!
            if (previousCore != null && !at.isAfter(previousCore)) break
            if (Duration.between(at, start) >= together) break
            before += photo
            start = at
        }
        return before.reversed() + inside + after
    }

    private fun nearest(photo: PreparedPhoto, indices: Collection<Int>, events: List<EventCandidate>): Int? =
        indices.minWithOrNull(
            compareBy<Int>({ distance(photo, events[it]) }, { events[it].core.start }, { it })
        )

    private fun distance(photo: PreparedPhoto, event: EventCandidate): Duration {
        val at = photo.instant!!
        return when {
            at.isBefore(event.core.start) -> Duration.between(at, event.core.start)
            at.isAfter(event.core.end) -> Duration.between(event.core.end, at)
            else -> Duration.ZERO
        }
    }
}
