package com.bunch.recommendation.domain.analysis

import com.bunch.global.image.PhotoImage

/**
 * Tells what one photo shows, whichever model does it (recommended bundle specification 4-2, 4-3). The model is a
 * detail of the class that implements this: the rules of the combination only read the [PhotoAnalysisResult], so the
 * model can be changed without touching them.
 *
 * A photo that cannot be analyzed is not given an empty answer. The class throws a [PhotoAnalysisFailure], and the
 * caller decides what the photo becomes (4-3, failed analysis).
 */
interface PhotoAnalyzer {
    fun analyze(image: PhotoImage): PhotoAnalysisResult
}
