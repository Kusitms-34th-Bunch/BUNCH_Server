package com.bunch.recommendation.domain.combination

/**
 * Cuts the day at the range of each event that is a bundle (recommended bundle specification 7-1, 7-2). The photos
 * that no event took are the input. They are in a chunk for each gap between the ranges, and the photos of the range
 * of an event that its bundle did not take (the leftover) are a chunk of their own, kept inside that range (they are
 * not joined to the photos outside). The range of an event that is not a bundle does not cut anything. A chunk with
 * no photo is dropped. A photo with a date only has no place on the time axis and is left out (7-9).
 */
class GeneralChunker {
    /** The photos of a chunk, in the order of the time, and where they come from, as text for the trace. */
    data class Chunk(
        val photos: List<PreparedPhoto>,
        val origin: String
    )

    fun chunks(day: DayPreparation.Ready, events: EventResult): List<Chunk> {
        val timed = day.photos.filter { it.instant != null }
        val taken = events.confirmed.flatMap { it.relatedPhotoIds + it.leftoverPhotoIds }.toSet()
        val ranges = events.confirmed.map { it.range }

        // A photo is in the gap that has as many ranges before it as the number of ranges that ended before the photo.
        val outside = timed.filter { it.photoId !in taken }
            .groupBy { photo -> ranges.count { it.end.isBefore(photo.instant!!) } }
            .toSortedMap().values
            .map { Chunk(it, OUTSIDE) }
        val inside = events.confirmed.map { event ->
            val leftover = event.leftoverPhotoIds.toSet()
            Chunk(timed.filter { it.photoId in leftover }, "$LEFTOVER${event.type}")
        }
        return (outside + inside).filter { it.photos.isNotEmpty() }
            .sortedBy { it.photos.first().instant }
    }

    companion object {
        const val OUTSIDE = "outside_event_range"
        const val LEFTOVER = "event_leftover:"
    }
}
