package com.bunch.recommendation.domain.analysis

import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.domain.combination.SensitiveClueCode
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Reads the answer of the model (recommended bundle specification 4-3) into what the combination uses. The same class
 * reads the answer when it comes and when it is read again from the database, so the stored answer and the live one
 * mean the same.
 *
 * The answer is trusted only as far as it can be checked. A code that is not in the lists of the specification, a
 * confidence that is not a number from 0 to 1 and a value that is not a code are left out, as if the model had not said
 * them: a photo is not made an event by a word that is no code. An answer that is not a JSON object cannot be read at
 * all and is a failure. A clue that is said twice keeps the higher confidence. The clues of the event and the sensitive
 * clues come in one list (4-1 F4) and are told apart by their codes.
 */
class AiAnswerParser(
    private val mapper: ObjectMapper = ObjectMapper()
) {
    fun parse(raw: String): AiAnalysis {
        val root = try {
            mapper.readTree(raw)
        } catch (ex: JsonProcessingException) {
            throw PhotoAnalysisFailure(PhotoAnalysisFailure.Reason.INVALID_RESPONSE, ex)
        }
        if (root == null || !root.isObject) {
            throw PhotoAnalysisFailure(PhotoAnalysisFailure.Reason.INVALID_RESPONSE)
        }

        val clues = readings(root.path(CLUES))
        return AiAnalysis(
            clues = clues.mapNotNull { (code, confidence) ->
                codeOf<EventClueCode>(code)?.let { Reading(it, confidence) }
            },
            sensitiveClues = clues.mapNotNull { (code, confidence) ->
                codeOf<SensitiveClueCode>(code)?.let { Reading(it, confidence) }
            },
            activity = reading(root.path(ACTIVITY))?.let { (code, confidence) ->
                codeOf<ActivityCode>(code)?.let { Reading(it, confidence) }
            },
            subject = codeOf<PhotoSubject>(textOf(root.path(SUBJECT))),
            people = codeOf<PeopleCount>(textOf(root.path(PEOPLE)))
        )
    }

    private fun textOf(node: JsonNode): String? = if (node.isTextual) node.textValue() else null

    // The clues as (code, confidence), in the order they came. A clue said twice is one, with the higher confidence.
    private fun readings(node: JsonNode): List<Pair<String, Double>> {
        if (!node.isArray) return emptyList()
        val best = LinkedHashMap<String, Double>()
        node.mapNotNull(::reading).forEach { (code, confidence) ->
            best.merge(code, confidence, ::maxOf)
        }
        return best.map { it.key to it.value }
    }

    private fun reading(node: JsonNode): Pair<String, Double>? {
        if (!node.isObject) return null
        val code = textOf(node.path(CODE)) ?: return null
        val confidenceNode = node.path(CONFIDENCE)
        if (!confidenceNode.isNumber) return null
        val confidence = confidenceNode.doubleValue()
        return if (confidence in 0.0..1.0) code to confidence else null
    }

    private inline fun <reified E : Enum<E>> codeOf(name: String?): E? =
        name?.let { enumValues<E>().firstOrNull { code -> code.name == it } }

    companion object {
        const val CLUES = "clues"
        const val ACTIVITY = "activity"
        const val SUBJECT = "subject"
        const val PEOPLE = "people"
        const val CODE = "code"
        const val CONFIDENCE = "conf"
    }
}
