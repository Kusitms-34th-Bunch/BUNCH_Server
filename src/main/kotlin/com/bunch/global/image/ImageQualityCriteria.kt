package com.bunch.global.image

/**
 * The numbers of the quality table (recommended bundle specification 4-4, 12). They are first values, to be moved when
 * the release shows what is wrong, so none of them is written into the calculation. The defaults are the table, a value
 * for a value.
 *
 * The image is made grey and shrunk to [analysisLongSide] on its long side before the sharpness and the exposure are
 * measured. A pixel at the end of the scale is one that is at most [darkPixelMax] or at least [brightPixelMin].
 *
 * A grade is low when any one of its conditions is met, and good when all of its conditions are met (4-4).
 */
data class ImageQualityCriteria(
    val analysisLongSide: Int = 1024,
    val darkPixelMax: Int = 5,
    val brightPixelMin: Int = 250,
    val lowSharpnessBelow: Double = 60.0,
    val goodSharpnessFrom: Double = 200.0,
    val lowMeanBrightnessBelow: Double = 35.0,
    val lowMeanBrightnessAbove: Double = 225.0,
    val lowExtremeShareAbove: Double = 0.40,
    val goodMeanBrightnessFrom: Double = 60.0,
    val goodMeanBrightnessTo: Double = 200.0,
    val goodExtremeShareBelow: Double = 0.10,
    val lowShortSideBelow: Int = 480,
    val goodShortSideFrom: Int = 1080
) {
    init {
        require(analysisLongSide >= 3) {
            "the image has to be at least three pixels wide to be measured"
        }
        require(darkPixelMax in 0..255 && brightPixelMin in 0..255 && darkPixelMax < brightPixelMin) {
            "the ends of the scale are inside 0 to 255 and the dark end is below the bright end"
        }
        require(lowSharpnessBelow >= 0.0 && lowSharpnessBelow <= goodSharpnessFrom) {
            "the sharpness of a good image is not below the one of a low image"
        }
        require(
            lowMeanBrightnessBelow <= goodMeanBrightnessFrom &&
                goodMeanBrightnessFrom <= goodMeanBrightnessTo &&
                goodMeanBrightnessTo <= lowMeanBrightnessAbove
        ) {
            "the brightness of a good image is inside the one of a not low image"
        }
        require(
            goodExtremeShareBelow in 0.0..1.0 && lowExtremeShareAbove in 0.0..1.0 &&
                goodExtremeShareBelow <= lowExtremeShareAbove
        ) {
            "the shares of the ends of the scale are from 0 to 1 and a good image has no more than a not low one"
        }
        require(lowShortSideBelow in 1..goodShortSideFrom) {
            "the short side of a good image is not below the one of a low image"
        }
    }
}
