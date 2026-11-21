package com.bunch.recommendation.domain.combination

/**
 * Takes the photos of a day that are on the time axis as one chunk to be cut into bundles (recommended bundle
 * specification 7-1). A photo with a date only has no place on the time axis and is left out (7-9). A day with no photo
 * that has a time gives no chunk.
 */
class GeneralChunker {
    /** The photos of a chunk, in the order of the time, and where they come from, as text for the trace. */
    data class Chunk(
        val photos: List<PreparedPhoto>,
        val origin: String
    )

    fun chunks(day: DayPreparation.Ready): List<Chunk> {
        val timed = day.photos.filter { it.instant != null }
        return listOf(Chunk(timed, DAY)).filter { it.photos.isNotEmpty() }
    }

    companion object {
        const val DAY = "day"
    }
}
