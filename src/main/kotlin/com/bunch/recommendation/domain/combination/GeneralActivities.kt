package com.bunch.recommendation.domain.combination

/**
 * What the general bundles read from the activity of the photos (recommended bundle specification 4-3, 5-1, 7-3). Only
 * usable photos vote, and a reading below [CombinationSettings.activityMinConfidence] counts as an unknown activity.
 * A photo whose analysis failed has no reading, so its activity is unknown (13-5 X7). Each photo is one vote, a photo
 * of a group of duplicates too: the specification counts the photos here and not the groups.
 */
class GeneralActivities(
    private val settings: CombinationSettings = CombinationSettings(),
    private val eligibility: BundleEligibility = BundleEligibility(settings)
) {
    fun activityOf(photo: PreparedPhoto): ActivityCode {
        val reading = photo.photo.analysis.activity ?: return ActivityCode.UNKNOWN
        return if (reading.confidence < settings.activityMinConfidence) ActivityCode.UNKNOWN else reading.code
    }

    /**
     * The main activity of [photos]: the one most of the usable photos show. It is unknown when half of them or
     * more are unknown, or when there is no usable photo. A tie goes to the activity with the bigger sum of
     * confidence, then to the first in the table of the activities (7-3).
     */
    fun representative(photos: Collection<PreparedPhoto>): ActivityCode {
        val usable = photos.filter(eligibility::isUsable)
        if (usable.isEmpty()) return ActivityCode.UNKNOWN
        val known = usable.filter { activityOf(it) != ActivityCode.UNKNOWN }
        if ((usable.size - known.size) * 2 >= usable.size) return ActivityCode.UNKNOWN
        return known.groupBy(::activityOf).entries
            .sortedWith(
                compareByDescending<Map.Entry<ActivityCode, List<PreparedPhoto>>> { it.value.size }
                    .thenByDescending { entry -> entry.value.sumOf { it.photo.analysis.activity!!.confidence } }
                    .thenBy { it.key.ordinal }
            )
            .first().key
    }

    /** The part of the day most of [photos] were taken in; a tie goes to the earlier part (7-3). */
    fun slotOf(photos: Collection<PreparedPhoto>): DayPart =
        photos.mapNotNull { it.localTime }
            .groupingBy { settings.dayPartOf(it.toLocalTime()) }
            .eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<DayPart, Int>> { it.value }.thenBy { it.key.ordinal })
            .first().key
}
