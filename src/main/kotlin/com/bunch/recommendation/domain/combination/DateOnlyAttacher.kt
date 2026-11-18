package com.bunch.recommendation.domain.combination

/**
 * Gives the photos with a date only to a bundle (recommended bundle specification 7-9, 7-10). A photo with a date only
 * is one that found no photo to borrow a time from (5-3), so it has no place on the time axis and no bundle took it. It
 * goes to the bundle that most of the photos of its batch with a time are in; when that does not tell, to the bundle
 * of the day if there is only one; and to none otherwise. A bundle with the same number of them is the one that starts
 * first, then the one with the lower photo number (1.8).
 *
 * Two photos are of one batch when they were uploaded together or their dates were set together (photo box 4.2.7 rule
 * 3). The bundles are already made, so the photos come too late to make a bundle stand: they only join it, are counted
 * in it, and are candidates for its card (8-1, photo box 4.2.8). The photo box specification has other rules for this
 * (the member who uploaded only to one group, the group with the most photos); the combination specification replaced
 * them, and it is the one followed.
 */
class DateOnlyAttacher(
    private val facts: BundleFacts = BundleFacts()
) {
    fun attach(day: DayPreparation.Ready, bundles: List<RecoBundle>): List<RecoBundle> {
        val taken = bundles.flatMap { bundle -> bundle.photos.map { it.photoId } }.toSet()
        val loose = day.photos.filter { it.instant == null && it.photoId !in taken }
        if (loose.isEmpty() || bundles.isEmpty()) return bundles

        val timed = day.photos.filter { it.instant != null }
        val bundleOf = bundles.flatMapIndexed { index, bundle -> bundle.photos.map { it.photoId to index } }.toMap()
        val target = loose.associateWith { targetOf(it, timed, bundleOf, bundles) }
        return bundles.mapIndexed { index, bundle ->
            val added = loose.filter { target[it] == index }
            if (added.isEmpty()) bundle else facts.add(bundle, day, added, PhotoRole.ATTACHED_DATE_ONLY)
        }
    }

    // The index of the bundle the photo goes to, or null.
    private fun targetOf(
        photo: PreparedPhoto,
        timed: List<PreparedPhoto>,
        bundleOf: Map<Long, Int>,
        bundles: List<RecoBundle>
    ): Int? {
        val inBundles = timed.filter { photo.photo.sharesBatchWith(it.photo) }
            .mapNotNull { bundleOf[it.photoId] }
            .groupingBy { it }.eachCount()
        val most = inBundles.entries.sortedWith(
            compareByDescending<Map.Entry<Int, Int>> { it.value }
                .thenBy { bundles[it.key].startAt }
                .thenBy { entry -> bundles[entry.key].photos.minOf { it.photoId } }
        ).firstOrNull()
        return most?.key ?: if (bundles.size == 1) 0 else null
    }
}
