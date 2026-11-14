package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.DayPart
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.Pace
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.SensitiveClueCode
import com.bunch.recommendation.domain.combination.SourceType
import com.bunch.recommendation.domain.combination.TimeState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The codes are the ones of recommended bundle specification 3-2 to 3-6, in the order the tables give them. */
class CombinationCodesTest {
    @Test
    fun `there are 61 event clues and the 8 sensitive clues of the table`() {
        assertThat(EventClueCode.entries).hasSize(61)
        assertThat(EventClueCode.entries.map { it.name }).doesNotHaveDuplicates()
        assertThat(SensitiveClueCode.entries.map { it.name }).containsExactly(
            "FUNERAL_WREATH",
            "MOURNING_CLOTHES",
            "FUNERAL_ALTAR",
            "MEMORIAL_PORTRAIT",
            "HOSPITAL_ROOM",
            "HOSPITAL_BED",
            "IV_DRIP",
            "SURGICAL_GOWN"
        )
        assertThat(EventClueCode.entries.map { it.name }).doesNotContainAnyElementsOf(
            SensitiveClueCode.entries.map { it.name }
        )
    }

    @Test
    fun `the 11 events have the pace of the table`() {
        assertThat(EventType.entries.filter { it.pace == Pace.SHORT })
            .containsExactly(EventType.BIRTHDAY, EventType.GRADUATION, EventType.YEAR_END)
        assertThat(EventType.entries.filter { it.pace == Pace.LONG })
            .containsExactly(EventType.WEDDING, EventType.CONCERT, EventType.SPORTS_VIEWING, EventType.COMPETITION)
        assertThat(EventType.entries.filter { it.pace == Pace.DAY })
            .containsExactly(EventType.TRAVEL, EventType.CAMPING, EventType.HOLIDAY, EventType.THEME_PARK)
        assertThat(EventType.entries).hasSize(11)
    }

    @Test
    fun `the activities are in the order of the table, which breaks a tie`() {
        assertThat(ActivityCode.entries.map { it.name }).containsExactly(
            "MEAL",
            "CAFE",
            "DRINKS",
            "CELEBRATION",
            "STUDY_WORK",
            "HOME",
            "WALK",
            "SHOW_EXHIBITION",
            "SPORTS",
            "SHOPPING",
            "OTHER",
            "SIGHTSEEING",
            "OUTDOOR",
            "TRANSIT",
            "UNKNOWN"
        )
    }

    @Test
    fun `the activities have the pace of the table and an unknown activity has none`() {
        val paceOf = ActivityCode.entries.associateWith { it.pace }

        assertThat(paceOf.filterValues { it == Pace.SHORT }.keys).containsExactly(
            ActivityCode.MEAL,
            ActivityCode.CAFE,
            ActivityCode.DRINKS,
            ActivityCode.CELEBRATION,
            ActivityCode.STUDY_WORK,
            ActivityCode.HOME,
            ActivityCode.WALK
        )
        assertThat(paceOf.filterValues { it == Pace.LONG }.keys).containsExactly(
            ActivityCode.SHOW_EXHIBITION,
            ActivityCode.SPORTS,
            ActivityCode.SHOPPING,
            ActivityCode.OTHER
        )
        assertThat(paceOf.filterValues { it == Pace.DAY }.keys).containsExactly(
            ActivityCode.SIGHTSEEING,
            ActivityCode.OUTDOOR,
            ActivityCode.TRANSIT
        )
        assertThat(ActivityCode.UNKNOWN.pace).isNull()
    }

    @ParameterizedTest
    @CsvSource(
        "SHORT,SHORT,SHORT",
        "SHORT,LONG,LONG",
        "LONG,SHORT,LONG",
        "SHORT,DAY,DAY",
        "DAY,SHORT,DAY",
        "LONG,LONG,LONG",
        "LONG,DAY,DAY",
        "DAY,LONG,DAY",
        "DAY,DAY,DAY"
    )
    fun `of two paces the longer one rules`(first: Pace, second: Pace, expected: Pace) {
        assertThat(first.longer(second)).isEqualTo(expected)
    }

    @Test
    fun `the other codes are the ones of the specification`() {
        assertThat(names(PhotoSubject.entries))
            .containsExactly("PERSON", "FOOD", "LANDSCAPE", "OBJECT", "ANIMAL", "DOCUMENT")
        assertThat(names(PeopleCount.entries)).containsExactly("P0", "P1", "P2", "P3_PLUS")
        assertThat(names(PhotoQuality.entries)).containsExactly("GOOD", "NORMAL", "LOW")
        assertThat(names(SourceType.entries)).containsExactly("CAMERA", "SCREENSHOT", "SAVED", "UNKNOWN")
        assertThat(names(TimeState.entries)).containsExactly("FULL", "DATE_ONLY", "UNKNOWN")
        assertThat(names(AiStatus.entries)).containsExactly("PENDING", "DONE", "FAILED")
        assertThat(names(BundleKind.entries)).containsExactly("EVENT", "GENERAL")
        assertThat(names(EstablishedBy.entries)).containsExactly("STRONG", "SUPPORT", "DATE_BOOST")
        assertThat(names(PhotoRole.entries)).containsExactly("CLUE", "RELATED", "GENERAL", "ATTACHED_DATE_ONLY")
        assertThat(names(DayPart.entries)).containsExactly("MORNING", "AFTERNOON", "NIGHT")
    }

    private fun names(entries: List<Enum<*>>): List<String> = entries.map { it.name }
}
