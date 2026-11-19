package com.bunch.recommendation.domain.combination

import java.time.LocalDate
import java.time.MonthDay

/**
 * The words a title is made of (recommended bundle specification 9-1, 3-4): the name of each event, the name that each
 * activity gives to a general bundle, and the name of each part of the day. They are kept in one place so the table of
 * the specification can be read against it.
 */
object BundleNames {
    /** The days of the year end that are the Christmas: the 24th and the 25th of December, by the day of the bundle. */
    private val CHRISTMAS_FROM = MonthDay.of(12, 24)
    private val CHRISTMAS_TO = MonthDay.of(12, 25)

    /**
     * The name of [event] for a bundle of the day [dayKey]. The year end is the Christmas on the 24th and the 25th of
     * December and the year end on the other days (9-1). The day is the day of the bundle (`day_key`), so a night
     * bundle that goes past midnight on the 24th is still of the 24th.
     */
    fun eventName(event: EventType, dayKey: LocalDate): String = when (event) {
        EventType.BIRTHDAY -> "생일 파티"
        EventType.GRADUATION -> "졸업"
        EventType.YEAR_END -> if (isChristmas(dayKey)) "크리스마스" else "연말"
        EventType.WEDDING -> "결혼식"
        EventType.CONCERT -> "공연·페스티벌"
        EventType.SPORTS_VIEWING -> "경기 관람"
        EventType.COMPETITION -> "대회"
        EventType.TRAVEL -> "여행"
        EventType.CAMPING -> "캠핑"
        EventType.HOLIDAY -> "명절"
        EventType.THEME_PARK -> "놀이공원"
    }

    /**
     * The name an activity gives to a general bundle, or null when it gives none: the other activity and the transit
     * are not shown (3-4), and the unknown activity is not a bundle at all.
     */
    fun activityName(activity: ActivityCode): String? = when (activity) {
        ActivityCode.MEAL -> "식사"
        ActivityCode.CAFE -> "카페·디저트"
        ActivityCode.DRINKS -> "술자리"
        ActivityCode.CELEBRATION -> "기념일"
        ActivityCode.STUDY_WORK -> "공부·업무"
        ActivityCode.HOME -> "일상"
        ActivityCode.WALK -> "산책"
        ActivityCode.SHOW_EXHIBITION -> "공연·전시"
        ActivityCode.SPORTS -> "운동"
        ActivityCode.SHOPPING -> "쇼핑"
        ActivityCode.SIGHTSEEING -> "나들이"
        ActivityCode.OUTDOOR -> "아웃도어"
        ActivityCode.OTHER,
        ActivityCode.TRANSIT,
        ActivityCode.UNKNOWN -> null
    }

    fun dayPartName(part: DayPart): String = when (part) {
        DayPart.MORNING -> "오전"
        DayPart.AFTERNOON -> "오후"
        DayPart.NIGHT -> "밤"
    }

    private fun isChristmas(dayKey: LocalDate): Boolean {
        val day = MonthDay.from(dayKey)
        return !day.isBefore(CHRISTMAS_FROM) && !day.isAfter(CHRISTMAS_TO)
    }
}
