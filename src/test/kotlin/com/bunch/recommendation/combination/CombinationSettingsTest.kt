package com.bunch.recommendation.combination

import com.bunch.global.image.ImageQualityCriteria
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.DayPart
import com.bunch.recommendation.domain.combination.Pace
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId

/** The defaults are the table of recommended bundle specification 12, a value for a value. */
class CombinationSettingsTest {
    private val settings = CombinationSettings()

    @Test
    fun `the day and its parts are the ones of the table`() {
        assertThat(settings.dayStartHour).isEqualTo(5)
        assertThat(settings.afternoonStartHour).isEqualTo(12)
        assertThat(settings.nightStartHour).isEqualTo(18)
        assertThat(settings.defaultZone).isEqualTo(ZoneId.of("Asia/Seoul"))
    }

    @Test
    fun `the gaps are the ones of the table`() {
        assertThat(settings.continuityShort).isEqualTo(Duration.ofHours(2))
        assertThat(settings.continuityLong).isEqualTo(Duration.ofHours(4))
        assertThat(settings.minBoundaryGap).isEqualTo(Duration.ofMinutes(20))
        assertThat(settings.eventSamePlaceGap).isEqualTo(Duration.ofHours(2))
    }

    @Test
    fun `the sizes of a bundle are the ones of the table`() {
        assertThat(settings.minPhotos).isEqualTo(6)
        assertThat(settings.minUploaders).isEqualTo(2)
        assertThat(settings.peopleSplitMinSide).isEqualTo(3)
        assertThat(settings.subBundleMax).isEqualTo(40)
        assertThat(settings.timeBuckets).isEqualTo(4)
        assertThat(settings.cardMaxSize).isEqualTo(10)
        assertThat(settings.cardSizeSteps).containsExactly(
            CombinationSettings.CardSizeStep(fromPhotos = 6, cardSize = 6),
            CombinationSettings.CardSizeStep(fromPhotos = 16, cardSize = 8),
            CombinationSettings.CardSizeStep(fromPhotos = 26, cardSize = 10)
        )
    }

    @Test
    fun `the ratios and the weights of the card are the ones of the table`() {
        assertThat(settings.clueMaxRatio).isEqualTo(0.5)
        assertThat(settings.subjectMaxRatio).isEqualTo(0.5)
        assertThat(settings.memberMaxRatio).isEqualTo(0.6)
        assertThat(settings.representativenessWeight).isEqualTo(0.4)
        assertThat(settings.qualityWeight).isEqualTo(0.2)
        assertThat(settings.diversityWeight).isEqualTo(0.4)
    }

    @Test
    fun `the confidences and the similarities are the ones of the table`() {
        assertThat(settings.clueMinConfidence).isEqualTo(0.70)
        assertThat(settings.sensitiveMinConfidence).isEqualTo(0.50)
        assertThat(settings.activityMinConfidence).isEqualTo(0.50)
        assertThat(settings.sameSceneMinSimilarity).isEqualTo(0.85)
    }

    @Test
    fun `the burst of one member with one device is two seconds, the new standard R1`() {
        assertThat(settings.burstMaxGap).isEqualTo(Duration.ofSeconds(2))
        assertThat(CombinationSettings(burstMaxGap = Duration.ofSeconds(3)).burstMaxGap)
            .isEqualTo(Duration.ofSeconds(3))
    }

    @Test
    fun `the similarities of the fingerprint start at their first numbers and can be moved`() {
        assertThat(settings.fingerprintBurstMinSimilarity).isEqualTo(0.90)
        assertThat(settings.fingerprintSameCompositionMinSimilarity).isEqualTo(0.95)

        val changed = CombinationSettings(fingerprintBurstMinSimilarity = 0.85)

        assertThat(changed.fingerprintBurstMinSimilarity).isEqualTo(0.85)
        assertThat(changed.fingerprintSameCompositionMinSimilarity).isEqualTo(0.95)
    }

    @Test
    fun `the numbers of the quality table are held with the others`() {
        assertThat(settings.quality).isEqualTo(ImageQualityCriteria())

        val changed = CombinationSettings(quality = ImageQualityCriteria(lowShortSideBelow = 600))

        assertThat(changed.quality.lowShortSideBelow).isEqualTo(600)
        assertThat(changed.quality.goodShortSideFrom).isEqualTo(1080)
    }

    @Test
    fun `the clock correction, the waits, the list and the windows are the ones of the table`() {
        assertThat(settings.clockCorrectionMinPairs).isEqualTo(2)
        assertThat(settings.clockCorrectionMinMedian).isEqualTo(Duration.ofMinutes(10))
        assertThat(settings.aiPendingTimeout).isEqualTo(Duration.ofMinutes(30))
        assertThat(settings.recalculationDebounce).isEqualTo(Duration.ofMinutes(5))
        assertThat(settings.listMax).isEqualTo(20)
        assertThat(settings.sameActivityGeneralMax).isEqualTo(3)
        assertThat(settings.yearEndWindows).containsExactly(
            CombinationSettings.MonthDayWindow(MonthDay.of(12, 24), MonthDay.of(12, 25)),
            CombinationSettings.MonthDayWindow(MonthDay.of(12, 31), MonthDay.of(1, 1))
        )
        assertThat(settings.holidayWindowDays).isEqualTo(1)
        assertThat(settings.configVersion).isEqualTo("reco-cfg-1")
    }

    @ParameterizedTest
    @CsvSource(
        "00:00,NIGHT",
        "04:59,NIGHT",
        "05:00,MORNING",
        "11:59,MORNING",
        "12:00,AFTERNOON",
        "17:59,AFTERNOON",
        "18:00,NIGHT",
        "23:59,NIGHT"
    )
    fun `a part of the day starts at its hour and ends before the next one`(time: LocalTime, expected: DayPart) {
        assertThat(settings.dayPartOf(time)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource("5,", "6,6", "15,6", "16,8", "25,8", "26,10", "300,10")
    fun `the card has the size of the step the bundle has reached`(photoCount: Int, expected: Int?) {
        assertThat(settings.cardSizeFor(photoCount)).isEqualTo(expected)
    }

    @Test
    fun `a short pace is two hours, a long pace four, and a day pace is not cut by time`() {
        assertThat(settings.continuityOf(Pace.SHORT)).isEqualTo(Duration.ofHours(2))
        assertThat(settings.continuityOf(Pace.LONG)).isEqualTo(Duration.ofHours(4))
        assertThat(settings.continuityOf(Pace.DAY)).isNull()
    }

    @Test
    fun `a value can be changed without a change of the code`() {
        val changed = CombinationSettings(minPhotos = 8, clueMinConfidence = 0.65, configVersion = "reco-cfg-2")

        assertThat(changed.minPhotos).isEqualTo(8)
        assertThat(changed.clueMinConfidence).isEqualTo(0.65)
        assertThat(changed.configVersion).isEqualTo("reco-cfg-2")
        assertThat(changed.minUploaders).isEqualTo(2)
    }

    @Test
    fun `numbers that cannot work together are refused`() {
        assertThatThrownBy { CombinationSettings(afternoonStartHour = 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CombinationSettings(nightStartHour = 24) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CombinationSettings(diversityWeight = 0.5) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CombinationSettings(memberMaxRatio = 0.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CombinationSettings(clueMinConfidence = 1.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CombinationSettings(fingerprintBurstMinSimilarity = 1.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CombinationSettings(fingerprintSameCompositionMinSimilarity = -0.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CombinationSettings(subBundleMax = 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CombinationSettings(cardMaxSize = 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CombinationSettings(cardSizeSteps = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CombinationSettings(
                cardSizeSteps = listOf(
                    CombinationSettings.CardSizeStep(16, 8),
                    CombinationSettings.CardSizeStep(6, 6)
                )
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
