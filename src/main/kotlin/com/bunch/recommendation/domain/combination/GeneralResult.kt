package com.bunch.recommendation.domain.combination

/**
 * What the general step finds in one day (recommended bundle specification 7). [bundles] are the general bundles, in
 * the order of the time. [notBundledPhotoIds] are the photos of the day that were put in a piece which is not a bundle
 * (too few photos or members, or no known activity, 7-8): they are not saved and stay in the photo box. A photo with a
 * date only is in neither, because the step that gives them to a bundle comes after this one (7-9).
 */
data class GeneralResult(
    val bundles: List<RecoBundle>,
    val notBundledPhotoIds: List<Long>
)
