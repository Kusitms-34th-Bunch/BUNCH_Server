package com.bunch.recommendation.domain.combination

/**
 * Counts the photos of a day the way a bundle does (recommended bundle specification 3-1): by the groups of duplicates
 * that have a usable photo, and the members by the ones who uploaded any of those groups. It is made for one day,
 * because the groups are the groups of that day.
 */
class GeneralCounter(
    private val eligibility: BundleEligibility,
    private val groups: List<DuplicateGroup>
) {
    fun photos(photos: Collection<PreparedPhoto>): Int = eligibility.photoCount(photos, groups)

    fun uploaders(photos: Collection<PreparedPhoto>): Set<Long> =
        eligibility.groupsOf(photos, groups).flatMap { it.uploaderIds }.toSet()

    /** The "ok" of the specification: enough photos and enough members for a bundle (7-4, 7-6, 7-7, 7-8). */
    fun isEnough(photos: Collection<PreparedPhoto>): Boolean = eligibility.isEnough(photos, groups)
}
