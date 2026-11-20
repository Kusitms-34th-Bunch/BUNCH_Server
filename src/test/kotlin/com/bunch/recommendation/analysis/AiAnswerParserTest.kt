package com.bunch.recommendation.analysis

import com.bunch.recommendation.domain.analysis.AiAnswerParser
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.domain.combination.SensitiveClueCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The answer of the model read into what the combination uses (recommended bundle specification 4-3). */
class AiAnswerParserTest {
    private val parser = AiAnswerParser()

    @Test
    fun `the answer of the specification is read as it is`() {
        val analysis = parser.parse(
            """{"clues":[{"code":"CANDLE_WHOLE_CAKE","conf":0.92},{"code":"PARTY_HAT","conf":0.71}],
                "activity":{"code":"DRINKS","conf":0.64},"subject":"PERSON","people":"P3_PLUS"}"""
        )

        assertThat(analysis).isEqualTo(
            AiAnalysis(
                clues = listOf(
                    Reading(EventClueCode.CANDLE_WHOLE_CAKE, 0.92),
                    Reading(EventClueCode.PARTY_HAT, 0.71)
                ),
                activity = Reading(ActivityCode.DRINKS, 0.64),
                subject = PhotoSubject.PERSON,
                people = PeopleCount.P3_PLUS
            )
        )
    }

    @Test
    fun `a low confidence is kept, the limits are applied when the combination is calculated`() {
        val analysis = parser.parse(
            """{"clues":[{"code":"BOUQUET","conf":0.05}],"activity":{"code":"MEAL","conf":0.1}}"""
        )

        assertThat(analysis.clues).containsExactly(Reading(EventClueCode.BOUQUET, 0.05))
        assertThat(analysis.activity).isEqualTo(Reading(ActivityCode.MEAL, 0.1))
    }

    @Test
    fun `the sensitive clues come in the list of the clues and are told apart by their codes`() {
        val analysis = parser.parse(
            """{"clues":[{"code":"FUNERAL_ALTAR","conf":0.8},{"code":"BOUQUET","conf":0.6},
                {"code":"IV_DRIP","conf":0.55}]}"""
        )

        assertThat(analysis.clues).containsExactly(Reading(EventClueCode.BOUQUET, 0.6))
        assertThat(analysis.sensitiveClues).containsExactly(
            Reading(SensitiveClueCode.FUNERAL_ALTAR, 0.8),
            Reading(SensitiveClueCode.IV_DRIP, 0.55)
        )
    }

    @Test
    fun `a code that is not in the lists is left out, the others are kept`() {
        val analysis = parser.parse(
            """{"clues":[{"code":"HALLOWEEN_PUMPKIN","conf":0.9},{"code":"TENT","conf":0.8}],
                "activity":{"code":"SKYDIVING","conf":0.9},"subject":"SPACESHIP","people":"P9"}"""
        )

        assertThat(analysis.clues).containsExactly(Reading(EventClueCode.TENT, 0.8))
        assertThat(analysis.activity).isNull()
        assertThat(analysis.subject).isNull()
        assertThat(analysis.people).isNull()
    }

    @Test
    fun `a confidence that is not a number from 0 to 1 leaves the clue out`() {
        val analysis = parser.parse(
            """{"clues":[{"code":"TENT","conf":1.5},{"code":"LANTERN","conf":-0.1},{"code":"CAMPFIRE","conf":"high"},
                {"code":"CAMPING_CHAIR"},{"code":"BBQ_GRILL","conf":1.0},{"code":"PASSPORT","conf":0.0}]}"""
        )

        assertThat(analysis.clues).containsExactly(
            Reading(EventClueCode.BBQ_GRILL, 1.0),
            Reading(EventClueCode.PASSPORT, 0.0)
        )
    }

    @Test
    fun `a clue said twice is one clue with the higher confidence`() {
        val analysis = parser.parse(
            """{"clues":[{"code":"TENT","conf":0.4},{"code":"LANTERN","conf":0.9},{"code":"TENT","conf":0.8}]}"""
        )

        assertThat(analysis.clues).containsExactly(
            Reading(EventClueCode.TENT, 0.8),
            Reading(EventClueCode.LANTERN, 0.9)
        )
    }

    @Test
    fun `what the answer does not say is not known`() {
        assertThat(parser.parse("{}")).isEqualTo(AiAnalysis.NONE)
    }

    @Test
    fun `a field of the wrong kind is not known`() {
        val analysis = parser.parse("""{"clues":"TENT","activity":"MEAL","subject":3,"people":["P1"]}""")

        assertThat(analysis).isEqualTo(AiAnalysis.NONE)
    }

    @Test
    fun `an answer that is not a JSON object cannot be read`() {
        listOf("", "not json", "[]", "\"MEAL\"", "42", "null").forEach { raw ->
            assertThatThrownBy { parser.parse(raw) }
                .isInstanceOfSatisfying(PhotoAnalysisFailure::class.java) {
                    assertThat(it.reason).isEqualTo(PhotoAnalysisFailure.Reason.INVALID_RESPONSE)
                }
        }
    }
}
