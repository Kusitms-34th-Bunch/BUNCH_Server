package com.bunch.recommendation.domain.combination

/**
 * The pictures a card can be made of (recommended bundle specification 4, first and second rule).
 *
 * Only the usable photos of the bundle are taken (the camera, not low quality, not a calculation exclusion), and of
 * each group of duplicates one photo stands for it: the one of better quality, then the one whose member has fewer
 * pictures in the bundle, then the one stored first. Of the members who uploaded a photo together, the one with the
 * fewest pictures in the bundle (the one who uploaded it first when they have as many, then the lower member number)
 * is the one the photo counts for.
 *
 * The pictures of a member are the groups of duplicates of the bundle that the member uploaded a photo of, a photo
 * uploaded together counted for each of the members. The groups are counted and not the photos, because the photo box
 * specification cleans the bursts before it counts the members.
 */
class CardPool(
    private val eligibility: BundleEligibility = BundleEligibility()
) {
    fun build(bundle: RecoBundle, day: DayPreparation.Ready): List<CardCandidate> {
        val byId = day.photos.associateBy { it.photoId }
        val usable = bundle.photos.mapNotNull { byId[it.photoId] }.filter(eligibility::isUsable)
        val groupOf = day.duplicateGroups.flatMap { group -> group.photoIds.map { it to group.id } }.toMap()
        val groups = usable.groupBy { groupOf[it.photoId] ?: it.photoId }

        val pictures = groups.values
            .flatMap { photos -> photos.flatMap { it.photo.uploads }.map { it.memberId }.toSet() }
            .groupingBy { it }.eachCount()

        return groups.values.map { photos ->
            val stand = photos.minWith(representative(pictures))
            CardCandidate(
                photo = stand,
                shareMember = shareMemberOf(stand, pictures),
                taker = takerOf(photos)
            )
        }.sortedWith(CardCandidate.byTime)
    }

    // Better quality first, then the photo of a member who has fewer pictures, then the one stored first.
    private fun representative(pictures: Map<Long, Int>): Comparator<PreparedPhoto> =
        compareBy<PreparedPhoto>(
            { if (it.photo.quality == PhotoQuality.GOOD) 0 else 1 },
            { pictures.getValue(shareMemberOf(it, pictures)) },
            { it.photo.uploadedAt },
            { it.photoId }
        )

    private fun shareMemberOf(photo: PreparedPhoto, pictures: Map<Long, Int>): Long =
        photo.photo.uploads.minWith(
            compareBy<RecoPhoto.Upload>({ pictures.getValue(it.memberId) }, { it.uploadedAt }, { it.memberId })
        ).memberId

    // The member who uploaded any photo of the group first, then the lower member number (as the together moment, R2).
    private fun takerOf(group: List<PreparedPhoto>): Long =
        group.flatMap { it.photo.uploads }.minWith(compareBy({ it.uploadedAt }, { it.memberId })).memberId
}
