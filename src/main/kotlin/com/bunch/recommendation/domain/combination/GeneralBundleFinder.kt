package com.bunch.recommendation.domain.combination

/**
 * The general bundles of a day (recommended bundle specification 3). It takes a day that is ready and makes bundles of
 * its photos, in this order: the photos of the day that have a time are one chunk, the chunk is cut into segments at
 * the gaps of 20 minutes or more (3-1), the first rule cuts it where the members change (3-2), the pieces are cut by
 * the second and the third rule where the part of the day changes or the gap is long, and a piece over the limit is
 * cut into sub bundles. A part is a bundle when it is recommended (3-4): enough photos and members, and the members
 * took photos together at least once. The others are not saved and stay in the photo box. There are no event bundles,
 * so every photo of the day that has a time is a photo of a part, a bundle or not.
 *
 * The bundles have no activity (the AI read it, and the combination does not use the AI any more), so it is `null` and
 * the trace has no stretches of activities.
 *
 * A photo with a date only is given to a bundle by the step after this one, so it is not in the result. A day on which
 * no photo has a time has no bundle: nothing there tells which photos were taken together (3-4, X1), so all its photos
 * are not bundled.
 */
class GeneralBundleFinder(
    private val settings: CombinationSettings = CombinationSettings(),
    private val eligibility: BundleEligibility = BundleEligibility(settings)
) {
    private val chunker = GeneralChunker()
    private val segmenter = GeneralSegmenter(settings)
    private val splitter = GeneralPeopleTimeSplitter(settings)
    private val bigSplitter = GeneralBigSplitter(settings)
    private val facts = BundleFacts(settings, eligibility)

    fun find(day: DayPreparation.Ready): GeneralResult {
        if (day.photos.none { it.instant != null }) {
            return GeneralResult(emptyList(), day.photos.map { it.photoId }.sorted())
        }
        val counter = GeneralCounter(eligibility, day.duplicateGroups)
        val bundles = mutableListOf<RecoBundle>()
        val notBundled = mutableListOf<Long>()
        for (chunk in chunker.chunks(day)) {
            val pieces = splitter.split(segmenter.segment(chunk.photos), listOf(chunk.origin), counter)
            for (piece in pieces) {
                val parts = bigSplitter.split(piece, counter)
                parts.forEachIndexed { index, part ->
                    if (counter.isRecommendable(part.photos)) {
                        bundles += bundle(day, part, if (parts.size > 1) index + 1 else null)
                    } else {
                        notBundled += part.photos.map { it.photoId }
                    }
                }
            }
        }
        return GeneralResult(
            bundles = bundles.sortedWith(compareBy({ it.startAt }, { bundle -> bundle.photos.minOf { it.photoId } })),
            notBundledPhotoIds = notBundled.sorted()
        )
    }

    private fun bundle(day: DayPreparation.Ready, part: GeneralPiece, subIndex: Int?): RecoBundle = facts.build(
        day = day,
        kind = BundleKind.GENERAL,
        photos = part.photos,
        roleOf = { PhotoRole.GENERAL },
        activity = null,
        subIndex = subIndex,
        span = TimeRange(part.start, part.end),
        general = RuleTrace.General(rules = part.rules, segments = emptyList(), activity = null)
    )
}
