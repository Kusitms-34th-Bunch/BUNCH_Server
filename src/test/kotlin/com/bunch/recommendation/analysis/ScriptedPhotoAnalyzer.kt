package com.bunch.recommendation.analysis

import com.bunch.global.image.PhotoImage
import com.bunch.recommendation.domain.analysis.AiAnswerParser
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.domain.analysis.PhotoAnalysisResult
import com.bunch.recommendation.domain.analysis.PhotoAnalyzer

/** An analyzer that answers as the test says, so the flow around it can be tested without a model service. */
class ScriptedPhotoAnalyzer : PhotoAnalyzer {
    var calls = 0
        private set

    /** What the analyzer throws instead of answering, or null to answer with [raw]. */
    var failure: PhotoAnalysisFailure? = null
    var raw: String = ANSWER

    override fun analyze(image: PhotoImage): PhotoAnalysisResult {
        calls++
        failure?.let { throw it }
        return PhotoAnalysisResult(AiAnswerParser().parse(raw), raw, MODEL_VERSION)
    }

    fun reset() {
        calls = 0
        failure = null
        raw = ANSWER
    }

    companion object {
        const val MODEL_VERSION = "test-model-1"
        const val ANSWER =
            """{"clues":[{"code":"TENT","conf":0.9}],"activity":{"code":"OUTDOOR","conf":0.8},""" +
                """"subject":"LANDSCAPE","people":"P0"}"""
    }
}
