package com.bunch.recommendation.service

import com.bunch.global.image.PhotoImageUrls
import com.bunch.recommendation.domain.RecommendationRepository
import com.bunch.recommendation.dto.RecommendedBundlePhoto
import com.bunch.recommendation.dto.RecommendedUploader
import org.springframework.stereotype.Component

/**
 * What a card and the photos of a card are made of, the same for the old recommended bundles and the ones of the new
 * combination (4.2.2, 4.2.5, 4.2.6): the members who uploaded the photos, and the photo as the viewer shows it.
 */
@Component
class RecommendedCardParts(private val recommendations: RecommendationRepository) {
    /** The members who uploaded each photo, as the badge shows them now (4.2.6). */
    fun uploadersOf(photoIds: Collection<Long>): Map<Long, List<RecommendedUploader>> =
        if (photoIds.isEmpty()) {
            emptyMap()
        } else {
            recommendations.findUploaderProfiles(photoIds.distinct())
                .groupBy({ it.photoId }, { RecommendedUploader(it.memberId, it.nickname, it.imageUrl) })
        }

    // The members of a card, the one who uploaded the most photos of it first and the lower member number first when
    // they uploaded as many. A photo several members uploaded counts for each of them. The specification does not say
    // in which order the chips go; this is the order the nicknames of a title are put in.
    fun chipsOf(uploadersOfPhotos: List<List<RecommendedUploader>>): List<RecommendedUploader> {
        val uploaders = uploadersOfPhotos.flatten()
        val photoCounts = uploaders.groupingBy { it.memberId }.eachCount()
        val mostPhotosFirst = compareByDescending<RecommendedUploader> { photoCounts.getValue(it.memberId) }

        return uploaders.distinctBy { it.memberId }.sortedWith(mostPhotosFirst.thenBy { it.memberId })
    }

    fun photoOf(row: RecommendationRepository.CardPhotoRow, uploaders: List<RecommendedUploader>) =
        RecommendedBundlePhoto(
            photoId = row.photoId,
            thumbnailUrl = PhotoImageUrls.thumbnail(row.imageUrl),
            imageUrl = PhotoImageUrls.full(row.imageUrl),
            capturedDate = row.capturedDate,
            capturedTime = row.capturedTime,
            uploaders = uploaders
        )
}
