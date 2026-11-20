package com.bunch.recommendation.domain.combination

import java.time.Instant
import java.time.LocalDate

/**
 * One recommended bundle the combination makes (recommended bundle specification 4-5). The stable key and the time it
 * is shown are not here: they come with the issue that keeps a bundle.
 *
 * The [title] is given at the end of the combination (9-1, [BundleNamer]); it is null while the bundle is not named
 * yet, and every bundle the combination gives back has one.
 *
 * [photos] are all photos of the bundle, with the place on the card of those that are on it (1 is the cover).
 * [photoCount] is the number of pictures, a group of copies counted once, not the size of [photos] (3-1).
 * [coverPhotoId] is null until the card is chosen. [subIndex] is the number of the part of a day that was cut for being
 * over the limit of photos (7-7), from 1. [activity] is the main activity of a general bundle.
 */
data class RecoBundle(
    val dayKey: LocalDate,
    val kind: BundleKind,
    val eventType: EventType?,
    val establishedBy: EstablishedBy?,
    val activity: ActivityCode?,
    val subIndex: Int?,
    val photos: List<BundlePhoto>,
    val coverPhotoId: Long?,
    val startAt: Instant,
    val endAt: Instant,
    val photoCount: Int,
    val uploaderCount: Int,
    val hasGroupPhoto: Boolean,
    val trace: RuleTrace,
    val title: String? = null
) {
    init {
        when (kind) {
            BundleKind.EVENT -> require(eventType != null && establishedBy != null) {
                "an event bundle has an event type and what made it stand"
            }

            BundleKind.GENERAL -> require(eventType == null && establishedBy == null) {
                "a general bundle has no event type and nothing that made an event stand"
            }
        }
        require(subIndex == null || subIndex >= 1) {
            "subIndex starts from 1"
        }
        require(!endAt.isBefore(startAt)) {
            "a bundle does not end before it starts"
        }
        require(photoCount >= 0 && uploaderCount >= 0) {
            "counts are not negative"
        }
        val positions = photos.mapNotNull { it.cardPosition }
        require(positions.all { it >= 1 } && positions.distinct().size == positions.size) {
            "the places on the card are from 1 and each is used once"
        }
        require(coverPhotoId == null || photos.any { it.photoId == coverPhotoId }) {
            "the cover is a photo of the bundle"
        }
        require(title == null || title.isNotBlank()) {
            "a title is not blank"
        }
    }

    /** A photo of the bundle, why it is in, and its place on the card if it is on it (4-5, 8-6). */
    data class BundlePhoto(
        val photoId: Long,
        val role: PhotoRole,
        val cardPosition: Int? = null
    )
}
