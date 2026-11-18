package com.bunch.recommendation.domain.combination

import com.bunch.global.image.ImageQualityCriteria
import java.time.Duration
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import kotlin.math.abs

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
 * The similarities of a burst and of the same composition come in two kinds because what a similarity is made of
 * changes its scale: the plain ones are for the cosine of two embeddings (the table), the ones of the fingerprint are
 * for [FingerprintSimilarity], the share of equal bits of two 64-bit fingerprints. Their first values are the same
 * numbers (0.90 is 6 different bits, 0.95 is 3) and are to be moved on real photos (14 V6). The gaps in time are the
 * same for both.
 *
 * The numbers of the quality table (4-4) are [quality]. The photo box calculates the quality when a photo is uploaded,
 * so the type of the numbers is in the shared image code and this class only holds them with the others (12).
 *
 * Some values are used by later steps only: the limits of the list, the wait before a recalculation, the wait for the
 * AI. They are here so that all numbers are at one place.
 */
data class CombinationSettings(
    val dayStartHour: Int = 5,
    val afternoonStartHour: Int = 12,
    val nightStartHour: Int = 18,
    val continuityShort: Duration = Duration.ofHours(2),
    val continuityLong: Duration = Duration.ofHours(4),
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
    val clueMaxRatio: Double = 0.5,
    val subjectMaxRatio: Double = 0.5,
    val memberMaxRatio: Double = 0.6,
    val timeBuckets: Int = 4,
    val eventSamePlaceGap: Duration = Duration.ofHours(2),
    val clueMinConfidence: Double = 0.70,
    val sensitiveMinConfidence: Double = 0.50,
    val activityMinConfidence: Double = 0.50,
    val sameSceneMinSimilarity: Double = 0.85,
    val burstMaxGap: Duration = Duration.ofSeconds(3),
    val burstMinSimilarity: Double = 0.90,
    val sameCompositionMaxGap: Duration = Duration.ofSeconds(120),
    val sameCompositionMinSimilarity: Double = 0.95,
    val fingerprintBurstMinSimilarity: Double = 0.90,
    val fingerprintSameCompositionMinSimilarity: Double = 0.95,
    val clockCorrectionMinPairs: Int = 2,
    val clockCorrectionMinMedian: Duration = Duration.ofMinutes(10),
    val representativenessWeight: Double = 0.4,
    val qualityWeight: Double = 0.2,
    val diversityWeight: Double = 0.4,
    val aiPendingTimeout: Duration = Duration.ofMinutes(30),
    val defaultZone: ZoneId = ZoneId.of("Asia/Seoul"),
    val quality: ImageQualityCriteria = ImageQualityCriteria(),
    val recalculationDebounce: Duration = Duration.ofMinutes(5),
    val listMax: Int = 20,
    val sameActivityGeneralMax: Int = 3,
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
        require(minPhotos >= 1 && minUploaders >= 1 && peopleSplitMinSide >= 1 && subBundleMax >= minPhotos) {
            "the sizes of a bundle are at least one and a part is not below the minimum"
        }
        require(cardSizeSteps.isNotEmpty()) {
            "there is at least one card size"
        }
        require(cardSizeSteps.zipWithNext().all { (before, after) -> before.fromPhotos < after.fromPhotos }) {
            "the card sizes grow with the number of photos"
        }
        require(listOf(clueMaxRatio, subjectMaxRatio, memberMaxRatio).all { it > 0.0 && it <= 1.0 }) {
            "a ratio is above 0 and at most 1"
        }
        require(
            listOf(
                clueMinConfidence,
                sensitiveMinConfidence,
                activityMinConfidence,
                sameSceneMinSimilarity,
                burstMinSimilarity,
                sameCompositionMinSimilarity,
                fingerprintBurstMinSimilarity,
                fingerprintSameCompositionMinSimilarity
            ).all { it in 0.0..1.0 }
        ) {
            "a confidence and a similarity are from 0 to 1"
        }
        require(abs(representativenessWeight + qualityWeight + diversityWeight - 1.0) < WEIGHT_TOLERANCE) {
            "the weights of the score add up to 1"
        }
    }

    /** The gap that still joins photos of this pace, or null for a day pace, which is not cut by time (3-5). */
    fun continuityOf(pace: Pace): Duration? = when (pace) {
        Pace.SHORT -> continuityShort
        Pace.LONG -> continuityLong
        Pace.DAY -> null
    }

    /** The part of the day a local time is in. A part starts at its hour and ends before the next one (3-1). */
    fun dayPartOf(time: LocalTime): DayPart = when (time.hour) {
        in dayStartHour until afternoonStartHour -> DayPart.MORNING
        in afternoonStartHour until nightStartHour -> DayPart.AFTERNOON
        else -> DayPart.NIGHT
    }

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

    private companion object {
        const val WEIGHT_TOLERANCE = 1e-9
    }
}
