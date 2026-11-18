package com.bunch.recommendation.domain.combination

/**
 * Gives a date and a time to the photos that miss them, from a photo that looks the same (recommended bundle
 * specification 5-3). It is done before the photos of a day are chosen, so a photo that gets a date takes part.
 *
 * A photo without a date takes the date and the time, and a photo with a date only takes the time, of a photo that has
 * both. What is borrowed is only for the calculation; a photo that is still without a date after this is left out.
 *
 * The specification does not say how the photo that looks the same is found when the photo has no time to compare
 * (its time is what is missing), nor which one to take when several do. This reads it so: the photos that have a date
 * and a time, of which the picture is as alike as the same composition needs ([minSimilarity]), and among them the most
 * alike one, then one uploaded in the same batch, then the earlier one, then the lower photo ID. A photo that was
 * borrowed from is never borrowed from again, so a date does not travel from one photo to the next.
 */
class DateBorrower(
    private val times: TimeNormalizer,
    private val similarity: ImageSimilarity,
    private val minSimilarity: Double
) {
    fun borrow(photos: List<TimedPhoto>): List<TimedPhoto> {
        val sources = photos.filter { it.hasTime }
        return photos.map { photo -> if (photo.hasTime) photo else borrowed(photo, sources) ?: photo }
    }

    private fun borrowed(photo: TimedPhoto, sources: List<TimedPhoto>): TimedPhoto? {
        val source = sources
            .mapNotNull { candidate -> alikeness(photo, candidate)?.let { candidate to it } }
            .sortedWith(
                compareByDescending<Pair<TimedPhoto, Double>> { it.second }
                    .thenByDescending { sameUploadBatch(photo, it.first) }
                    .thenBy { times.instant(it.first) }
                    .thenBy { it.first.photoId }
            )
            .firstOrNull()?.first ?: return null
        return photo.copy(
            date = photo.date ?: source.date,
            time = source.time,
            offset = source.offset,
            borrowedFrom = source.photoId
        )
    }

    private fun alikeness(photo: TimedPhoto, candidate: TimedPhoto): Double? =
        similarity.similarity(photo.photo, candidate.photo)?.takeIf { it >= minSimilarity }

    private fun sameUploadBatch(photo: TimedPhoto, candidate: TimedPhoto): Boolean {
        val batches = candidate.photo.uploads.map { it.uploadBatchId }.toSet()
        return photo.photo.uploads.any { it.uploadBatchId in batches }
    }
}
