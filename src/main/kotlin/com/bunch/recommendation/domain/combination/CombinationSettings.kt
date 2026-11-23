package com.bunch.recommendation.domain.combination

import com.bunch.global.image.ImageQualityCriteria
import java.time.Duration
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId

/**
 * Every number of the combination (recommended bundle specification 12). None of them is written into the rules: they
 * are first values, to be moved when the release shows what is wrong (1, 12). The defaults are the table of the
 * specification, a value for a value. [configVersion] is written into the trace of every bundle, so a bundle can be
 * tied to the numbers that made it.
 *
 * A day starts at [dayStartHour] and runs to the same hour of the next day (3-1). Of the three parts of it, the
 * morning starts at [dayStartHour], the afternoon at [afternoonStartHour] and the night at [nightStartHour], and the
 * night goes on to the start of the next day.
 *
 * The gaps that cut a bundle (2-2, 3-2): [peopleChangeGap] is the gap after which a change of all the members is a
 * change of the bunch (1 hour); [slotChangeGap] is the gap after which a change of the part of the day cuts (2 hours);
 * [sameSlotGap] is the gap after which even the same part of the day is cut (4 hours). [minBoundaryGap] is the gap that
 * makes a boundary at all (20 minutes).
 *
 * [burstMaxGap] is the gap of a burst of the same member with the same device (the duplicate group R1, 2 seconds).
 *
 * [togetherWindow] is how close in time the photos of two different members must be to make a moment they took together
 * (the together moment R2, 30 minutes). A gap of exactly this length still counts.
 *
 * [clockErrorMargin] is how far later than its upload a photo without an offset may be taken before its upload batch is
 * taken for a wrong clock (exception X5, 14 hours: the biggest difference between two time zones). A photo with an
 * offset is compared without it.
 *
 * The numbers of the quality table (4-4) are [quality]. The photo box calculates the quality when a photo is uploaded,
 * so the type of the numbers is in the shared image code and this class only holds them with the others (12).
 *
 * [cardSizeSteps] is the table of the size of the card (4): 6 pictures for 6 to 15 pictures of the bundle, 8 for 16
 * to 25, 10 from 26. [cardWindow] is the 10 minutes the choice of the pictures looks at (4): the distance from the
 * pictures that are chosen, the moment of the members around a picture, and the window that holds at most half of
 * the card.
 *
 * Some values are used by later steps only: the limits of the list, the wait before a recalculation, the wait for the
 * AI. They are here so that all numbers are at one place.
 */
data class CombinationSettings(
    val dayStartHour: Int = 5,
    val afternoonStartHour: Int = 12,
    val nightStartHour: Int = 18,
    val peopleChangeGap: Duration = Duration.ofHours(1),
    val slotChangeGap: Duration = Duration.ofHours(2),
    val sameSlotGap: Duration = Duration.ofHours(4),
    val minBoundaryGap: Duration = Duration.ofMinutes(20),
    val minPhotos: Int = 6,
    val minUploaders: Int = 2,
    val peopleSplitMinSide: Int = 3,
    val subBundleMax: Int = 40,
    val cardSizeSteps: List<CardSizeStep> = listOf(
        CardSizeStep(fromPhotos = 6, cardSize = 6),
        CardSizeStep(fromPhotos = 16, cardSize = 8),
        CardSizeStep(fromPhotos = 26, cardSize = 10)
    ),
    val memberMaxRatio: Double = 0.6,
    val cardWindow: Duration = Duration.ofMinutes(10),
    val timeBuckets: Int = 4,
    val eventSamePlaceGap: Duration = Duration.ofHours(2),
    val clueMinConfidence: Double = 0.70,
    val sensitiveMinConfidence: Double = 0.50,
    val activityMinConfidence: Double = 0.50,
    val burstMaxGap: Duration = Duration.ofSeconds(2),
    val togetherWindow: Duration = Duration.ofMinutes(30),
    val clockErrorMargin: Duration = Duration.ofHours(14),
    val aiPendingTimeout: Duration = Duration.ofMinutes(30),
    val defaultZone: ZoneId = ZoneId.of("Asia/Seoul"),
    val quality: ImageQualityCriteria = ImageQualityCriteria(),
    val recalculationDebounce: Duration = Duration.ofMinutes(5),
    val listMax: Int = 20,
    val sameActivityGeneralMax: Int = 3,
    val expireDays: Int = 30,
    val yearEndWindows: List<MonthDayWindow> = listOf(
        MonthDayWindow(MonthDay.of(12, 24), MonthDay.of(12, 25)),
        MonthDayWindow(MonthDay.of(12, 31), MonthDay.of(1, 1))
    ),
    val holidayWindowDays: Int = 1,
    val configVersion: String = "reco-cfg-1"
) {
    init {
        require(0 <= dayStartHour && dayStartHour < afternoonStartHour && afternoonStartHour < nightStartHour) {
            "the parts of the day start in order from the start of the day"
        }
        require(nightStartHour <= 23) {
            "the night starts on the day it belongs to"
        }
        require(expireDays >= 1) {
            "a recommended bundle is shown for at least one day"
        }
        require(minPhotos >= 1 && minUploaders >= 1 && peopleSplitMinSide >= 1 && subBundleMax >= minPhotos) {
            "the sizes of a bundle are at least one and a part is not below the minimum"
        }
        require(!burstMaxGap.isNegative && togetherWindow > Duration.ZERO) {
            "a burst gap is not negative and the together window is above zero"
        }
        require(cardSizeSteps.isNotEmpty()) {
            "there is at least one card size"
        }
        require(cardSizeSteps.zipWithNext().all { (before, after) -> before.fromPhotos < after.fromPhotos }) {
            "the card sizes grow with the number of photos"
        }
        require(cardSizeSteps.all { it.cardSize in 1..it.fromPhotos } && cardWindow > Duration.ZERO) {
            "a card is not bigger than the bundle it is made for, and the window of the card is above zero"
        }
        require(memberMaxRatio > 0.0 && memberMaxRatio <= 1.0) {
            "a ratio is above 0 and at most 1"
        }
        require(
            listOf(
                clueMinConfidence,
                sensitiveMinConfidence,
                activityMinConfidence
            ).all { it in 0.0..1.0 }
        ) {
            "a confidence is from 0 to 1"
        }
    }

    /** The part of the day a local time is in. A part starts at its hour and ends before the next one (3-1). */
    fun dayPartOf(time: LocalTime): DayPart = when (time.hour) {
        in dayStartHour until afternoonStartHour -> DayPart.MORNING
        in afternoonStartHour until nightStartHour -> DayPart.AFTERNOON
        else -> DayPart.NIGHT
    }

    /** The part of the day most of [photos] were taken in; a tie goes to the earlier part (3-1). */
    fun mainDayPartOf(photos: Collection<PreparedPhoto>): DayPart =
        photos.mapNotNull { it.localTime }
            .groupingBy { dayPartOf(it.toLocalTime()) }
            .eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<DayPart, Int>> { it.value }.thenBy { it.key.ordinal })
            .first().key

    /** The photos on the card of a bundle of [photoCount] pictures, or null below the smallest bundle (8-1). */
    fun cardSizeFor(photoCount: Int): Int? = cardSizeSteps.lastOrNull { photoCount >= it.fromPhotos }?.cardSize

    /** From [fromPhotos] pictures the card has [cardSize] photos, until the next step starts (8-1). */
    data class CardSizeStep(
        val fromPhotos: Int,
        val cardSize: Int
    )

    /** A stretch of the year, from a month and day to a month and day, which may run into the next year (12). */
    data class MonthDayWindow(
        val from: MonthDay,
        val to: MonthDay
    )
}
