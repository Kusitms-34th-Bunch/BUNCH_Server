package com.bunch.recommendation.domain.combination

import java.time.Instant

/**
 * The general bundles of a day (recommended bundle specification 7). It takes a day that is ready and the events of the
 * day, and makes bundles of the photos no event took, in this order: the day is cut at the range of the events (7-2),
 * each chunk into segments (7-3), the pieces are cut where the members or the time and the activity change (7-4), the
 * pieces become sections of one activity (7-5), sections too small to stand are joined (7-6), and a section over the
 * limit is cut into sub bundles (7-7). A part is a bundle when it is enough and its main activity is known (7-8); the
 * others are not saved and stay in the photo box.
 *
 * A photo with a date only is given to a bundle by the step after this one (7-9), so it is not in the result. The
 * exception is a day on which no photo has a time: the photo box specification (4.2.7) takes the day as one group of
 * photos, so the day is one part. The specification of the combination says nothing of that day; the bundle has no time
 * of its own and is given the start of the day.
 */
class GeneralBundleFinder(
    private val settings: CombinationSettings = CombinationSettings(),
    private val eligibility: BundleEligibility = BundleEligibility(settings)
) {
    private val activities = GeneralActivities(settings, eligibility)
    private val chunker = GeneralChunker()
    private val segmenter = GeneralSegmenter(settings, activities)
    private val splitter = GeneralPeopleTimeSplitter(settings)
    private val sections = GeneralActivitySections()
    private val balancer = GeneralSectionBalancer()
    private val bigSplitter = GeneralBigSplitter(settings)
    private val facts = BundleFacts(settings, eligibility)

    fun find(
        day: DayPreparation.Ready,
        events: EventResult = EventResult(confirmed = emptyList(), failed = emptyList())
    ): GeneralResult {
        val counter = GeneralCounter(eligibility, day.duplicateGroups)
        if (day.photos.none { it.instant != null }) return wholeDay(day, counter)

        val bundles = mutableListOf<RecoBundle>()
        val notBundled = mutableListOf<Long>()
        for (chunk in chunker.chunks(day, events)) {
            val segments = segmenter.segment(chunk.photos, counter)
            for (piece in splitter.split(segments, listOf(chunk.origin), counter)) {
                val balanced = balancer.balance(sections.build(piece.segments), counter)
                val inPiece = balanced.sections.map { TimedActivity(it.activity, TimeRange(it.start, it.end)) }
                for (section in balanced.sections) {
                    val parts = bigSplitter.split(section, counter)
                    parts.forEachIndexed { index, part ->
                        val photos = part.section.photos
                        if (isBundle(photos, counter)) {
                            bundles += bundle(
                                day = day,
                                photos = photos,
                                activity = section.activity,
                                subIndex = if (parts.size > 1) index + 1 else null,
                                start = part.section.start,
                                end = part.section.end,
                                rules = piece.rules + balanced.rules + part.rules,
                                sections = inPiece
                            )
                        } else {
                            notBundled += photos.map { it.photoId }
                        }
                    }
                }
            }
        }
        return GeneralResult(
            bundles = bundles.sortedWith(compareBy({ it.startAt }, { bundle -> bundle.photos.minOf { it.photoId } })),
            notBundledPhotoIds = notBundled.sorted()
        )
    }

    // 7-8: enough photos and members, and an activity that is known.
    private fun isBundle(photos: List<PreparedPhoto>, counter: GeneralCounter): Boolean =
        counter.isEnough(photos) && activities.representative(photos) != ActivityCode.UNKNOWN

    private fun wholeDay(day: DayPreparation.Ready, counter: GeneralCounter): GeneralResult {
        val photos = day.photos
        if (!isBundle(photos, counter)) return GeneralResult(emptyList(), photos.map { it.photoId }.sorted())
        val start = day.dayKey.atTime(settings.dayStartHour, 0).atZone(settings.defaultZone).toInstant()
        val bundle = bundle(
            day = day,
            photos = photos,
            activity = activities.representative(photos),
            subIndex = null,
            start = start,
            end = start,
            rules = listOf(NO_TIME),
            sections = emptyList()
        )
        return GeneralResult(listOf(bundle), emptyList())
    }

    /** A stretch of one activity of the piece a bundle was cut from, for the trace (7-5). */
    private data class TimedActivity(val activity: ActivityCode, val range: TimeRange)

    private fun bundle(
        day: DayPreparation.Ready,
        photos: List<PreparedPhoto>,
        activity: ActivityCode,
        subIndex: Int?,
        start: Instant,
        end: Instant,
        rules: List<String>,
        sections: List<TimedActivity>
    ): RecoBundle = facts.build(
        day = day,
        kind = BundleKind.GENERAL,
        photos = photos,
        roleOf = { PhotoRole.GENERAL },
        activity = activity,
        subIndex = subIndex,
        span = TimeRange(start, end),
        general = RuleTrace.General(
            rules = rules,
            segments = sections.map { RuleTrace.ActivitySegment(it.activity, it.range) },
            activity = activity
        )
    )

    companion object {
        const val NO_TIME = "no_time_whole_day"
    }
}
