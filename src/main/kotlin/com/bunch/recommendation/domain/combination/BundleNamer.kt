package com.bunch.recommendation.domain.combination

import java.time.LocalDate

/**
 * Gives the bundles of one day their titles (recommended bundle specification 9-1). The title is made of the date, the
 * part of the day, and what the bundle is, by the kind of the bundle:
 *
 * - an event that stood by a strong clue: the date and the name of the event, 「10월 19일 생일 파티」;
 * - an event that stood by a supporting clue or by the date: the date and the part of the day, 「10월 19일 밤」, with no
 *   name of the event, because a name that is wrong is worse than none;
 * - a general bundle: the date, the part of the day and the name of its activity, 「10월 19일 밤 식사」. When the
 *   activity gives no name (the other activity, the transit) the title is the date and the part of the day.
 *
 * The date is the day of the bundle (`day_key`), with the year in front when it is not the year of [today]. The part of
 * the day is the one most of the photos of the bundle were taken in, counted as the general bundles count it. When the
 * titles of two bundles of the day are the same, each gets a number in the order the bundles start, 「… (1)」, 「… (2)」;
 * the parts of a day that was cut for its size are numbered the same way.
 *
 * Nothing here reads the time of the clock: [today] is given, so the same bundles give the same titles.
 */
class BundleNamer(
    private val settings: CombinationSettings = CombinationSettings()
) {
    /** The bundles of [day], each with its title. The bundles come back in the order they were given. */
    fun name(bundles: List<RecoBundle>, day: DayPreparation.Ready, today: LocalDate): List<RecoBundle> {
        val photos = day.photos.associateBy { it.photoId }
        val bases = bundles.map { baseOf(it, photos, today) }
        val order = bundles.indices.sortedWith(
            compareBy<Int>({ bundles[it].startAt }).thenBy { index -> bundles[index].photos.minOf { it.photoId } }
        )
        val numbers = HashMap<Int, Int>()
        order.groupBy { bases[it] }.values.filter { it.size > 1 }.forEach { same ->
            same.forEachIndexed { position, index -> numbers[index] = position + 1 }
        }
        return bundles.mapIndexed { index, bundle ->
            bundle.copy(title = numbers[index]?.let { "${bases[index]} ($it)" } ?: bases[index])
        }
    }

    private fun baseOf(bundle: RecoBundle, photos: Map<Long, PreparedPhoto>, today: LocalDate): String {
        val date = dateOf(bundle.dayKey, today)
        if (bundle.kind == BundleKind.EVENT && bundle.establishedBy == EstablishedBy.STRONG) {
            return "$date ${BundleNames.eventName(bundle.eventType!!, bundle.dayKey)}"
        }
        val activityName = bundle.activity?.takeIf { bundle.kind == BundleKind.GENERAL }?.let(BundleNames::activityName)
        val dayPartName = dayPartOf(bundle, photos)?.let(BundleNames::dayPartName)
        return listOfNotNull(date, dayPartName, activityName).joinToString(" ")
    }

    // The part of the day of the bundle, or null when none of its photos has a time to tell it (a day of photos with a
    // date only).
    private fun dayPartOf(bundle: RecoBundle, photos: Map<Long, PreparedPhoto>): DayPart? {
        val inBundle = bundle.photos.mapNotNull { photos[it.photoId] }
        return if (inBundle.any { it.localTime != null }) settings.mainDayPartOf(inBundle) else null
    }

    private fun dateOf(dayKey: LocalDate, today: LocalDate): String {
        val year = if (dayKey.year == today.year) "" else "${dayKey.year}년 "
        return "$year${dayKey.monthValue}월 ${dayKey.dayOfMonth}일"
    }
}
