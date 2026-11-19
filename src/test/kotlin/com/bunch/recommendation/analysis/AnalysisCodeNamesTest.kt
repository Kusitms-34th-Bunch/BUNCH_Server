package com.bunch.recommendation.analysis

import com.bunch.recommendation.domain.analysis.AnalysisCodeNames
import com.bunch.recommendation.domain.analysis.AnalysisPrompt
import com.bunch.recommendation.domain.analysis.AnalysisSchema
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.SensitiveClueCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The model is offered exactly the codes of the specification, each with its name (specification 3-2, 3-4, 3-6). */
class AnalysisCodeNamesTest {
    @Test
    fun `every code the combination knows has a name, so none reaches the model without one`() {
        assertThat(AnalysisCodeNames.eventClues.keys).containsExactlyInAnyOrderElementsOf(EventClueCode.entries)
        assertThat(AnalysisCodeNames.sensitiveClues.keys).containsExactlyInAnyOrderElementsOf(SensitiveClueCode.entries)
        assertThat(AnalysisCodeNames.subjects.keys).containsExactlyInAnyOrderElementsOf(PhotoSubject.entries)
        assertThat(AnalysisCodeNames.people.keys).containsExactlyInAnyOrderElementsOf(PeopleCount.entries)
    }

    @Test
    fun `the activities are the 14 of the specification, the unknown one is decided by the server`() {
        assertThat(AnalysisCodeNames.activities.keys)
            .containsExactlyInAnyOrderElementsOf(ActivityCode.entries - ActivityCode.UNKNOWN)
        assertThat(AnalysisCodeNames.activities).hasSize(14)
    }

    @Test
    fun `the instructions list every code with its name`() {
        val system = AnalysisPrompt.SYSTEM

        val names = AnalysisCodeNames.eventClues.map { (code, name) -> code.name to name } +
            AnalysisCodeNames.sensitiveClues.map { (code, name) -> code.name to name } +
            AnalysisCodeNames.activities.map { (code, name) -> code.name to name } +
            AnalysisCodeNames.subjects.map { (code, name) -> code.name to name } +
            AnalysisCodeNames.people.map { (code, name) -> code.name to name }

        names.forEach { (code, name) -> assertThat(system).contains("- $code: $name") }
        assertThat(names).hasSize(EventClueCode.entries.size + SensitiveClueCode.entries.size + 14 + 6 + 4)
        assertThat(system).doesNotContain("UNKNOWN")
    }

    @Test
    fun `the instructions keep the scenes that are not clues and never ask who the people are`() {
        val system = AnalysisPrompt.SYSTEM

        assertThat(system).contains("카페의 조각 케이크", "고깃집 불판", "동네 놀이터, 쇼핑몰 안의 작은 회전목마")
        assertThat(system).contains("알아보려 하지 않는다")
    }

    @Test
    fun `the schema asks for all four parts and offers only the codes of the lists`() {
        @Suppress("UNCHECKED_CAST")
        val properties = AnalysisSchema.SCHEMA["properties"] as Map<String, Map<String, Any>>

        assertThat(AnalysisSchema.SCHEMA["required"]).isEqualTo(listOf("clues", "activity", "subject", "people"))
        assertThat(AnalysisSchema.SCHEMA["additionalProperties"]).isEqualTo(false)
        assertThat(properties.getValue("subject")["enum"]).isEqualTo(PhotoSubject.entries.map { it.name })
        assertThat(properties.getValue("people")["enum"]).isEqualTo(PeopleCount.entries.map { it.name })
    }
}
