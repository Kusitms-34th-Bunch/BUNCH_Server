package com.bunch.recommendation.service

import com.bunch.recommendation.domain.RecommendationRepository
import com.bunch.recommendation.dto.RecommendedUploader
import org.springframework.stereotype.Component

/**
 * The members that a card of a recommended bundle shows (photo box 4.2.2, 4.2.6): the members who uploaded the photos
 * of the card, as they are now. The old and the new recommended bundles show them the same way, so it is here once.
 */
@Component
class RecommendedUploaders(
    private val recommendations: RecommendationRepository
) {
    /** The members who uploaded each photo, as the badge shows them: the nickname and the profile image now. */
    fun of(photoIds: List<Long>): Map<Long, List<RecommendedUploader>> =
        recommendations.findUploaderProfiles(photoIds.distinct())
            .groupBy({ it.photoId }, { RecommendedUploader(it.memberId, it.nickname, it.imageUrl) })

    /**
     * The members of a card, the one who uploaded the most photos of it first and the lower member number first when
     * they uploaded as many. A photo several members uploaded counts for each of them. The specification does not say
     * in which order the chips go; this is the order the nicknames of a title are put in.
     */
    fun chipsOf(uploadersOfPhotos: List<List<RecommendedUploader>>): List<RecommendedUploader> {
        val uploaders = uploadersOfPhotos.flatten()
        val photoCounts = uploaders.groupingBy { it.memberId }.eachCount()
        val mostPhotosFirst = compareByDescending<RecommendedUploader> { photoCounts.getValue(it.memberId) }

        return uploaders.distinctBy { it.memberId }.sortedWith(mostPhotosFirst.thenBy { it.memberId })
    }
}
