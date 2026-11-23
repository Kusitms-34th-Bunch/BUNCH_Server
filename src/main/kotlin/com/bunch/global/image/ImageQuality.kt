package com.bunch.global.image

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tells how good an image is for a recommended bundle: how sharp it is, how well it is exposed and how large it is
 * (recommended bundle specification 4-4). No AI and no library is used, only the image reader of the JDK. The image is
 * read at a reduced size, so a large photo is not decoded in full.
 *
 * The sharpness is the variance of the Laplacian of the grey image, the exposure is the mean brightness and the share
 * of the pixels at the ends of the scale. The size is the short side of the image that was given, which is the photo
 * the server holds: the app shrinks a photo before it sends it (photo box 3.2.2), so the server does not know the
 * size of the file it was shrunk from.
 */
class ImageQuality(
    private val criteria: ImageQualityCriteria = ImageQualityCriteria()
) {
    /** What was measured of an image, before the numbers of the criteria are applied. */
    data class Measure(
        val sharpness: Double,
        val meanBrightness: Double,
        val extremeShare: Double,
        val shortSide: Int
    )

    /** The grade of the image, or null when the image cannot be read. */
    fun of(image: PhotoImage): ImageQualityGrade? = measure(image)?.let(::gradeOf)

    /** What was measured of the image, or null when the image cannot be read. */
    fun measure(image: PhotoImage): Measure? = try {
        ImageDecodeLimiter.withPermit { grey(image.bytes)?.let { measureOf(it, min(image.width, image.height)) } }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    fun gradeOf(measure: Measure): ImageQualityGrade {
        val low = measure.sharpness < criteria.lowSharpnessBelow ||
            measure.meanBrightness < criteria.lowMeanBrightnessBelow ||
            measure.meanBrightness > criteria.lowMeanBrightnessAbove ||
            measure.extremeShare > criteria.lowExtremeShareAbove ||
            measure.shortSide < criteria.lowShortSideBelow
        if (low) return ImageQualityGrade.LOW

        val good = measure.sharpness >= criteria.goodSharpnessFrom &&
            measure.meanBrightness in criteria.goodMeanBrightnessFrom..criteria.goodMeanBrightnessTo &&
            measure.extremeShare < criteria.goodExtremeShareBelow &&
            measure.shortSide >= criteria.goodShortSideFrom
        return if (good) ImageQualityGrade.GOOD else ImageQualityGrade.NORMAL
    }

    private fun grey(bytes: ByteArray): BufferedImage? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        return input.use {
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val step = max(1, max(reader.getWidth(0), reader.getHeight(0)) / criteria.analysisLongSide)
                val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                shrunkGrey(reader.read(0, param))
            } finally {
                reader.dispose()
            }
        }
    }

    // The image on a grey scale, shrunk so that its long side is at most the one of the criteria.
    private fun shrunkGrey(source: BufferedImage): BufferedImage {
        val scale = min(1.0, criteria.analysisLongSide.toDouble() / max(source.width, source.height))
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        val grey = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = grey.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return grey
    }

    private fun measureOf(grey: BufferedImage, shortSide: Int): Measure {
        val pixels = (grey.raster.dataBuffer as DataBufferByte).data
        var sum = 0L
        var extremes = 0L
        for (pixel in pixels) {
            val value = pixel.toInt() and BYTE_MASK
            sum += value
            if (value <= criteria.darkPixelMax || value >= criteria.brightPixelMin) extremes++
        }
        return Measure(
            sharpness = laplacianVariance(pixels, grey.width, grey.height),
            meanBrightness = sum.toDouble() / pixels.size,
            extremeShare = extremes.toDouble() / pixels.size,
            shortSide = shortSide
        )
    }

    // The variance of the Laplacian (the pixel four times, minus the four pixels around it) of the inner pixels. An
    // image that is too small to have an inner pixel has nothing sharp in it.
    private fun laplacianVariance(pixels: ByteArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0L
        var squares = 0L
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val at = y * width + x
                val laplacian = 4 * (pixels[at].toInt() and BYTE_MASK) -
                    (pixels[at - 1].toInt() and BYTE_MASK) - (pixels[at + 1].toInt() and BYTE_MASK) -
                    (pixels[at - width].toInt() and BYTE_MASK) - (pixels[at + width].toInt() and BYTE_MASK)
                sum += laplacian
                squares += laplacian.toLong() * laplacian
            }
        }
        val count = (width - 2).toLong() * (height - 2)
        return squares.toDouble() / count - (sum.toDouble() / count) * (sum.toDouble() / count)
    }

    private companion object {
        const val BYTE_MASK = 0xff
    }
}
