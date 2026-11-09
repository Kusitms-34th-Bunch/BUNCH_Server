package com.bunch.recommendation.domain

import java.time.LocalDate
import java.time.LocalTime

/**
 * Makes the [TitleContext] of a cluster out of the values the recalculation has read (4.2.2 rule 3). It reads nothing
 * itself.
 *
 * The nicknames are the members who uploaded photos of the cluster, the one with the most photos first. The
 * earliest photo and the end of the cluster before it are taken from every photo of the bunch that has a date,
 * also the ones a bundle uses, so a bundle that took the earlier photos does not make a gathering look like a long
 * time after the last one.
 */
class TitleContextBuilder {
    /**
     * [datedPhotos] are all photos of the bunch with a date, the photos of [cluster] among them. [nicknames] maps a
     * member to the nickname. [visibleTitles] and [recentSentenceIds] are passed on as they are.
     */
    fun build(
        cluster: PhotoCluster,
        nicknames: Map<Long, String>,
        bunchMemberCount: Int,
        datedPhotos: List<PhotoSample>,
        visibleTitles: Set<String>,
        recentSentenceIds: List<String>
    ): TitleContext {
        val own = cluster.photoIds.toSet()
        val photos = datedPhotos.filter { it.photoId in own }

        return TitleContext(
            startDate = cluster.startDate,
            endDate = cluster.endDate,
            photoTimes = photos.mapNotNull { it.capturedTime },
            uploaderNicknames = nicknamesByPhotoCount(photos, nicknames),
            bunchMemberCount = bunchMemberCount,
            earliestPhotoDate = datedPhotos.minOfOrNull { it.capturedDate } ?: cluster.startDate,
            previousEndDate = previousEndDate(cluster.startDate, own, photos, datedPhotos),
            visibleTitles = visibleTitles,
            recentSentenceIds = recentSentenceIds
        )
    }

    // A photo several members uploaded together counts for each of them. A tie goes to the member who came first.
    private fun nicknamesByPhotoCount(photos: List<PhotoSample>, nicknames: Map<Long, String>): List<String> {
        val photoCounts = photos.flatMap { it.uploaderIds }.groupingBy { it }.eachCount()
        val members = photoCounts.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

        return members.mapNotNull { nicknames[it] }.filter { it.isNotBlank() }
    }

    // The latest date of a photo that is not in the cluster and was taken before it. A photo of the first day that is
    // earlier than the cluster's first shooting time counts, so an earlier gathering on the same day is not a long
    // time ago. A photo of that day without a time cannot be placed and does not count.
    private fun previousEndDate(
        startDate: LocalDate,
        own: Set<Long>,
        photos: List<PhotoSample>,
        datedPhotos: List<PhotoSample>
    ): LocalDate? {
        val firstTime = photos
            .filter { it.capturedDate == startDate }
            .mapNotNull { it.capturedTime }
            .minOrNull()

        return datedPhotos
            .filter { it.photoId !in own && isBefore(it, startDate, firstTime) }
            .maxOfOrNull { it.capturedDate }
    }

    private fun isBefore(photo: PhotoSample, startDate: LocalDate, firstTime: LocalTime?): Boolean {
        if (photo.capturedDate.isBefore(startDate)) return true

        val time = photo.capturedTime
        return photo.capturedDate == startDate && firstTime != null && time != null && time.isBefore(firstTime)
    }
}
