package com.bunch.recommendation.domain.combination

/**
 * Which photos count for a bundle and whether a set of photos is enough to be one (recommended bundle specification
 * 3-1, 5-1, 8-2). The event bundles and the general bundles count the same way, so it is here once.
 *
 * A usable photo is one that was taken with the camera, is not of low quality and is not a document. A photo with no
 * quality or no subject is not low and not a document (3-1, 4-3). The photos are counted by their groups of duplicates,
 * one for each, and the members by the ones who uploaded any photo of those groups, a photo uploaded together included.
 * A set of photos is enough when it has [CombinationSettings.minPhotos] groups and [CombinationSettings.minUploaders]
 * members (the bundle can stand, 3-1). It is recommended when it is enough and the members took photos together at
 * least once (3-4, [TogetherMoments]). The members of the two conditions are counted differently on purpose: the first
 * counts every member who uploaded a photo, a copy another member uploaded again included, and the second counts the
 * member who uploaded it first only.
 */
class BundleEligibility(
    private val settings: CombinationSettings = CombinationSettings()
) {
    private val together = TogetherMoments(settings.togetherWindow, ::isUsable)

    fun isUsable(photo: PreparedPhoto): Boolean =
        photo.photo.source == SourceType.CAMERA &&
            photo.photo.quality != PhotoQuality.LOW &&
            photo.photo.analysis.subject != PhotoSubject.DOCUMENT

    /** The groups of duplicates that have a usable photo among [photos]. */
    fun groupsOf(photos: Collection<PreparedPhoto>, groups: List<DuplicateGroup>): List<DuplicateGroup> {
        val groupOf = groups.flatMap { group -> group.photoIds.map { it to group } }.toMap()
        return photos.filter(::isUsable).mapNotNull { groupOf[it.photoId] }.distinctBy { it.id }
    }

    fun photoCount(photos: Collection<PreparedPhoto>, groups: List<DuplicateGroup>): Int =
        groupsOf(photos, groups).size

    fun uploaderCount(photos: Collection<PreparedPhoto>, groups: List<DuplicateGroup>): Int =
        groupsOf(photos, groups).flatMap { it.uploaderIds }.toSet().size

    fun isEnough(photos: Collection<PreparedPhoto>, groups: List<DuplicateGroup>): Boolean {
        val counted = groupsOf(photos, groups)
        val members = counted.flatMap { it.uploaderIds }.toSet()
        return counted.size >= settings.minPhotos && members.size >= settings.minUploaders
    }

    /** The moments the members of [photos] took photos together (R2), counted among [photos] only. */
    fun togetherMoments(photos: Collection<PreparedPhoto>, groups: List<DuplicateGroup>): TogetherMoments.Result =
        together.find(photos, groups)

    /** The recommendation condition of 3-4: enough photos and members, and a together moment at least once. */
    fun isRecommendable(photos: Collection<PreparedPhoto>, groups: List<DuplicateGroup>): Boolean =
        isEnough(photos, groups) && togetherMoments(photos, groups).exists
}
