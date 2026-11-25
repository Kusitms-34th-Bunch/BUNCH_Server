package com.bunch.recommendation.domain.combination

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Gives the bundles of one day their titles (recommended bundle specification 4, the name of a bundle). The title is
 * the date and the part of the day, 「10월 19일 밤」, and nothing of what the photos show (they are not read).
 *
 * The date is the calendar date of the time the bundle starts (D7: a bundle is dated by its start), with the year in
 * front when it is not the year of [today]. A bundle that starts between midnight and 05:00 is of the next date, though
 * its day (`day_key`) is the one before; a bundle with no time to start from has the date of its day. The part of
 * the day is the one that [CombinationSettings.dayPartMainShare] (70%) or more of the photos of the bundle that have a
 * time were taken in. When no part has that many, the two parts with the most photos are put together in the order of
 * the day, 「10월 19일 오후~밤」, so that a bundle of a cafe in the morning and a lunch in the afternoon is not named
 * after the afternoon alone (G9). A photo with a date only has no time to vote with.
 *
 * When two bundles or more of the day have the same title, each of them is given the time it starts, 「10월 19일 오후
 * 2시」: the part of the day the first photo is in and the hour on a clock of 12 hours. When they start in the same hour
 * too, the minute is put to the ones that start in it, 「10월 19일 오후 2시 40분」, so that no two titles of a day are the
 * same (the bundles of a day are at least 20 minutes apart).
 *
 * Nothing here reads the time of the clock: [today] is given, so the same bundles give the same titles.
 */
class BundleNamer(
    private val settings: CombinationSettings = CombinationSettings()
) {
    /** The bundles of [day], each with its title. The bundles come back in the order they were given. */
    fun name(bundles: List<RecoBundle>, day: DayPreparation.Ready, today: LocalDate): List<RecoBundle> {
        val photos = day.photos.associateBy { it.photoId }
        val forms = bundles.mapIndexed { index, bundle -> formOf(index, bundle, photos, today) }
        val titles = forms.groupBy { it.base }.values
            .flatMap { same -> if (same.size == 1) listOf(same[0].index to same[0].base) else withStartTime(same) }
            .toMap()
        return bundles.mapIndexed { index, bundle -> bundle.copy(title = titles.getValue(index)) }
    }

    // What a title is made of for a bundle: the title without a time, and the time the bundle starts, when it has one.
    private data class Form(
        val index: Int,
        val date: String,
        val base: String,
        val start: LocalDateTime?
    )

    private fun formOf(index: Int, bundle: RecoBundle, photos: Map<Long, PreparedPhoto>, today: LocalDate): Form {
        val inBundle = bundle.photos.mapNotNull { photos[it.photoId] }
        val start = startOf(inBundle)
        val date = dateOf(start?.toLocalDate() ?: bundle.dayKey, today)
        val parts = partsOf(inBundle)?.let(::partsName)
        return Form(
            index = index,
            date = date,
            base = listOfNotNull(date, parts).joinToString(" "),
            start = start
        )
    }

    // The titles of bundles that have the same title: each with the time it starts, and with the minute too where the
    // hour is the same. A bundle with no time to start from keeps the title it has.
    private fun withStartTime(same: List<Form>): List<Pair<Int, String>> {
        val byHour = same.groupBy { hourTitle(it) }
        return same.map { form ->
            val title = hourTitle(form)
            val sharesHour = form.start != null && byHour.getValue(title).size > 1
            form.index to if (sharesHour) minuteTitle(form) else title
        }
    }

    private fun hourTitle(form: Form): String =
        form.start?.let { "${form.date} ${timeName(it, withMinute = false)}" } ?: form.base

    private fun minuteTitle(form: Form): String = "${form.date} ${timeName(form.start!!, withMinute = true)}"

    // 「오후 2시」, and 「오후 2시 40분」 with the minute. The minute is left out when it is none, 「오후 2시」, so the one that
    // starts at the hour and the one that starts later in it differ.
    private fun timeName(start: LocalDateTime, withMinute: Boolean): String {
        val part = BundleNames.dayPartName(settings.dayPartOf(start.toLocalTime()))
        val hour = if (start.hour % HALF_DAY == 0) HALF_DAY else start.hour % HALF_DAY
        val minute = if (withMinute && start.minute > 0) " ${start.minute}분" else ""
        return "$part ${hour}시$minute"
    }

    // The part of the day most of the photos are in, or the two parts with the most photos in the order of the day, or
    // null when none of the photos has a time.
    private fun partsOf(photos: List<PreparedPhoto>): List<DayPart>? {
        val counts = photos.mapNotNull { it.localTime }
            .groupingBy { settings.dayPartOf(it.toLocalTime()) }
            .eachCount()
        val total = counts.values.sum()
        if (total == 0) return null
        val byPhotos = counts.entries.sortedWith(
            compareByDescending<Map.Entry<DayPart, Int>> { it.value }.thenBy { it.key }
        )
        val main = byPhotos.first()
        if (main.value >= settings.dayPartMainShare * total - SHARE_TOLERANCE) return listOf(main.key)
        return byPhotos.take(2).map { it.key }.sorted()
    }

    private fun partsName(parts: List<DayPart>): String = parts.joinToString("~") { BundleNames.dayPartName(it) }

    // The time the bundle starts: where the first of its photos that has a time was taken.
    private fun startOf(photos: List<PreparedPhoto>): LocalDateTime? = photos
        .filter { it.instant != null && it.localTime != null }
        .minWithOrNull(compareBy({ it.instant }, { it.photoId }))
        ?.localTime

    private fun dateOf(date: LocalDate, today: LocalDate): String {
        val year = if (date.year == today.year) "" else "${date.year}년 "
        return "$year${date.monthValue}월 ${date.dayOfMonth}일"
    }

    private companion object {
        const val HALF_DAY = 12
        const val SHARE_TOLERANCE = 1e-9
    }
}
