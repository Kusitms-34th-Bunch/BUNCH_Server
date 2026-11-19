package com.bunch.recommendation.domain.analysis

import com.bunch.recommendation.domain.combination.AiAnalysis

/**
 * What an analysis of one photo gave. The [raw] answer is kept, not only what was read of it (4-3: "the raw answer is
 * stored"), because the limits of the confidence are applied when the combination is calculated, and a changed limit
 * must be able to read the same answer again. The database keeps its content, not its spacing or the order of its keys.
 * The [modelVersion] tells which model gave it.
 */
data class PhotoAnalysisResult(
    val analysis: AiAnalysis,
    val raw: String,
    val modelVersion: String
)
