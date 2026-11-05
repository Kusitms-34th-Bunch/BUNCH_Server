package com.bunch.recommendation.domain

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.regex.Pattern
import kotlin.random.Random

/**
 * Makes the title of a recommended bundle out of the sentence list (4.2.2 rule 3).
 *
 * The first situation that fits decides which sentences are used. One of them is taken at random, leaving out the
 * titles already in the list and the sentences used lately. When nothing is left the next source is tried: the
 * sentences with names, then the recent sentences again, then the sentences of the default situation. A sentence that
 * is longer than the limit once its blanks are filled is never taken.
 *
 * [DB 연동 지점] 이 클래스는 DB를 읽거나 쓰지 않는다. 입력(TitleContext)은 #70이 DB에서 모아 넘기고, 결과
 * (RecommendedTitle)는 #70이 후보 테이블에 저장한다. 문장은 TitleSentenceSource로, 특별한 날은 SpecialDayCalendar로
 * 읽으므로 두 곳을 DB 구현으로 바꿔도 이 클래스는 고치지 않는다.
 */
class TitleGenerator(
    private val sentences: TitleSentenceSource,
    private val calendar: SpecialDayCalendar,
    private val settings: RecommendationSettings = RecommendationSettings(),
    private val random: Random = Random.Default
) {
    fun generate(context: TitleContext): RecommendedTitle {
        val active = sentences.sentences().filter { it.active }
        val recent = context.recentSentenceIds.take(settings.recentSentenceCount).toSet()
        val own = pool(active, situationOf(context), context)
        val names = pool(active, TitleSituation.MEMBER_NAMES, context)
        val basic = pool(active, TitleSituation.DEFAULT, context)
        val titles = context.visibleTitles

        val stages = listOf(
            { pick(own, context, recent, titles) },
            { pick(names, context, recent, titles) },
            { pick(own, context, emptySet(), titles) },
            { pick(basic, context, emptySet(), titles) },
            { pick(active, context, emptySet(), emptySet()) }
        )
        val triedFirst = settings.blankSentenceRate > 0.0 && random.nextDouble() < settings.blankSentenceRate
        val order = if (triedFirst) listOf(stages[1], stages[0]) + stages.drop(2) else stages

        return order.firstNotNullOfOrNull { it() } ?: error("There is no sentence to make a title of.")
    }

    private fun situationOf(context: TitleContext): TitleSituation = when {
        calendar.specialDay(context.startDate) != null -> TitleSituation.SPECIAL_DAY
        !context.startDate.isAfter(context.earliestPhotoDate) -> TitleSituation.FIRST_GATHERING
        context.bunchMemberCount >= EVERYONE_MIN_MEMBERS &&
            context.uploaderNicknames.size >= context.bunchMemberCount -> TitleSituation.EVERYONE
        isLongGap(context) -> TitleSituation.AFTER_LONG_GAP
        context.endDate != null -> TitleSituation.PAST_MIDNIGHT
        else -> TitleSituation.DEFAULT
    }

    private fun isLongGap(context: TitleContext): Boolean {
        val previous = context.previousEndDate ?: return false
        return ChronoUnit.DAYS.between(previous, context.startDate) >= settings.longGapDays
    }

    private fun pool(
        active: List<TitleSentence>,
        situation: TitleSituation,
        context: TitleContext
    ): List<TitleSentence> {
        val details = detailsOf(situation, context)
        return active.filter { it.situation == situation && (details == null || it.detail in details) }
    }

    // The details of a situation that are used now, or null when every sentence of the situation is used.
    private fun detailsOf(situation: TitleSituation, context: TitleContext): Set<String>? = when (situation) {
        TitleSituation.SPECIAL_DAY ->
            setOfNotNull(calendar.specialDay(context.startDate)?.name?.lowercase())
        TitleSituation.DEFAULT -> defaultDetails(context)
        TitleSituation.MEMBER_NAMES -> nameDetails(context)
        else -> null
    }

    // The weekday and the time of day. A sentence of the day itself (a Monday, a Saturday evening) is used together
    // with the sentences of the weekend or the weekdays. Without a shooting time only the weekday is looked at: the
    // sentences for a missing time, and the Monday ones, which do not depend on the time of day.
    private fun defaultDetails(context: TitleContext): Set<String> {
        val day = context.startDate.dayOfWeek
        val time = middleOf(context.photoTimes)
            ?: return setOfNotNull(if (day == DayOfWeek.MONDAY) "monday" else null, NO_TIME)
        val slot = when {
            time.hour < MORNING_FROM -> "dawn"
            time.hour < AFTERNOON_FROM -> "morning"
            time.hour < EVENING_FROM -> "afternoon"
            else -> "evening"
        }
        val weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
        val ofTheDay = when {
            day == DayOfWeek.SATURDAY && slot == "evening" -> "saturday_evening"
            day == DayOfWeek.SUNDAY && slot == "evening" -> "sunday_evening"
            day == DayOfWeek.FRIDAY && slot == "evening" -> "friday_evening"
            day == DayOfWeek.MONDAY -> "monday"
            else -> null
        }
        return setOfNotNull(ofTheDay, "${if (weekend) "weekend" else "weekday"}_$slot")
    }

    private fun nameDetails(context: TitleContext): Set<String> {
        val uploaders = context.uploaderNicknames.size
        val forCount = when {
            uploaders >= 3 -> "three_plus"
            uploaders == 2 -> "two"
            else -> null
        }
        return setOfNotNull(forCount, "any")
    }

    // The middle of the shooting times: the middle one, or the mean of the two in the middle.
    private fun middleOf(times: List<LocalTime>): LocalTime? {
        if (times.isEmpty()) return null
        val seconds = times.map { it.toSecondOfDay() }.sorted()
        val middle = seconds.size / 2
        val value = if (seconds.size % 2 == 1) seconds[middle] else (seconds[middle - 1] + seconds[middle]) / 2
        return LocalTime.ofSecondOfDay(value.toLong())
    }

    private fun pick(
        pool: List<TitleSentence>,
        context: TitleContext,
        leftOut: Set<String>,
        titles: Set<String>
    ): RecommendedTitle? {
        val fitting = pool
            .filter { it.id !in leftOut }
            .mapNotNull { sentence -> fill(sentence, context)?.let { sentence to it } }
            .filter { (_, title) -> title !in titles && letters(title) <= settings.maxTitleLength }
        if (fitting.isEmpty()) return null

        val (sentence, title) = fitting[random.nextInt(fitting.size)]
        return RecommendedTitle(title, sentence.id)
    }

    // The sentence with its blanks filled, or null when a blank has nothing to be filled with.
    private fun fill(sentence: TitleSentence, context: TitleContext): String? {
        val nicknames = context.uploaderNicknames
        var text = sentence.text
        if ("{A}" in text) {
            val first = nicknames.getOrNull(0) ?: return null
            text = text.replace("{A}", first)
        }
        if ("{B}" in text) {
            val second = nicknames.getOrNull(1) ?: return null
            text = text.replace("{B}", second)
        }
        return text
            .replace("{N}", (nicknames.size - 1).toString())
            .replace("{요일}", context.startDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN))
    }

    private fun letters(text: String): Long = GRAPHEME.matcher(text).results().count()

    private companion object {
        const val EVERYONE_MIN_MEMBERS = 3
        const val NO_TIME = "no_time"
        const val MORNING_FROM = 5
        const val AFTERNOON_FROM = 12
        const val EVENING_FROM = 17
        val GRAPHEME: Pattern = Pattern.compile("\\X")
    }
}
