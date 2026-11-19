package com.bunch.recommendation.domain.analysis

import com.bunch.recommendation.domain.analysis.AiAnswerParser.Companion.ACTIVITY
import com.bunch.recommendation.domain.analysis.AiAnswerParser.Companion.CLUES
import com.bunch.recommendation.domain.analysis.AiAnswerParser.Companion.CODE
import com.bunch.recommendation.domain.analysis.AiAnswerParser.Companion.CONFIDENCE
import com.bunch.recommendation.domain.analysis.AiAnswerParser.Companion.PEOPLE
import com.bunch.recommendation.domain.analysis.AiAnswerParser.Companion.SUBJECT

/**
 * The JSON schema of the answer (recommended bundle specification 4-3), made of plain maps so that this class knows no
 * model service. The code lists are the lists of [AnalysisCodeNames], so the model can only answer with a known code.
 * The parser still checks the answer: a schema narrows what the model says, it is not what the combination relies on.
 */
object AnalysisSchema {
    val SCHEMA: Map<String, Any> = objectOf(
        CLUES to mapOf(
            "type" to "array",
            "items" to reading(
                AnalysisCodeNames.eventClues.keys.map { it.name } +
                    AnalysisCodeNames.sensitiveClues.keys.map { it.name }
            )
        ),
        ACTIVITY to reading(AnalysisCodeNames.activities.keys.map { it.name }),
        SUBJECT to enumOf(AnalysisCodeNames.subjects.keys.map { it.name }),
        PEOPLE to enumOf(AnalysisCodeNames.people.keys.map { it.name })
    )

    private fun reading(codes: List<String>): Map<String, Any> = objectOf(
        CODE to enumOf(codes),
        CONFIDENCE to mapOf("type" to "number")
    )

    private fun enumOf(values: List<String>): Map<String, Any> = mapOf(
        "type" to "string",
        "enum" to values
    )

    // An object that needs all of its properties and nothing else, which is what a structured answer asks for.
    private fun objectOf(vararg properties: Pair<String, Any>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(*properties),
        "required" to properties.map { it.first },
        "additionalProperties" to false
    )
}
