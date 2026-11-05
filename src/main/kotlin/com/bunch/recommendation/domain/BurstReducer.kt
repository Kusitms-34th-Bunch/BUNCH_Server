package com.bunch.recommendation.domain

import java.time.Duration

/**
 * Keeps one photo of each burst of nearly identical shots (4.2.8).
 *
 * Only photos of the same uploaders are compared, so the member of a dropped shot always keeps the shot that stays.
 * The shots of one uploader group are put in shooting order and neighbours that are close in time and alike in
 * their image fingerprint belong to the same burst. The shot in the middle of a burst stays. Photos without a time or
 * a fingerprint are never reduced. The photos that stay keep the order of the input.
 */
class BurstReducer(private val settings: RecommendationSettings = RecommendationSettings()) {
    fun reduce(photos: List<PhotoSample>): List<PhotoSample> {
        val dropped = photos
            .filter { it.moment != null && it.fingerprint != null }
            .groupBy { it.uploaderIds }
            .values
            .flatMap { bursts(it.sortedWith(compareBy({ photo -> photo.moment }, { photo -> photo.photoId }))) }
            .filter { it.size > 1 }
            .flatMap { burst -> burst.filterNot { it === burst[(burst.size - 1) / 2] } }
            .map { it.photoId }
            .toSet()
        return photos.filter { it.photoId !in dropped }
    }

    private fun bursts(sorted: List<PhotoSample>): List<List<PhotoSample>> {
        val bursts = mutableListOf<MutableList<PhotoSample>>()
        for (photo in sorted) {
            val current = bursts.lastOrNull()
            if (current != null && alike(current.last(), photo)) {
                current += photo
            } else {
                bursts += mutableListOf(photo)
            }
        }
        return bursts
    }

    private fun alike(before: PhotoSample, after: PhotoSample): Boolean {
        val beforePrint = before.fingerprint ?: return false
        val afterPrint = after.fingerprint ?: return false
        return Duration.between(before.moment, after.moment) <= settings.burstGap &&
            java.lang.Long.bitCount(beforePrint xor afterPrint) <= settings.burstMaxDifferentBits
    }
}
