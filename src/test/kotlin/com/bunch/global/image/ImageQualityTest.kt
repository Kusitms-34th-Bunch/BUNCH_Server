package com.bunch.global.image

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** The quality of an image (recommended bundle specification 4-4): sharpness, exposure and size. */
class ImageQualityTest {
    private val quality = ImageQuality()

    @Test
    fun `a large sharp well exposed image is good`() {
        assertThat(quality.of(checkerboard(1600, 1200, dark = 60, light = 200))).isEqualTo(ImageQualityGrade.GOOD)
    }

    @Test
    fun `a large image is measured at a reduced size and is still good`() {
        assertThat(quality.of(checkerboard(3000, 2000, dark = 60, light = 200, cell = 12)))
            .isEqualTo(ImageQualityGrade.GOOD)
    }

    @Test
    fun `a sharp well exposed image of a middle size is normal`() {
        assertThat(quality.of(checkerboard(1000, 800, dark = 60, light = 200))).isEqualTo(ImageQualityGrade.NORMAL)
    }

    @Test
    fun `a blurred image without detail is low`() {
        assertThat(quality.of(flat(1600, 1200, 128))).isEqualTo(ImageQualityGrade.LOW)
    }

    @Test
    fun `a dark image and a washed out image are low`() {
        assertThat(quality.of(checkerboard(1600, 1200, dark = 5, light = 35))).isEqualTo(ImageQualityGrade.LOW)
        assertThat(quality.of(checkerboard(1600, 1200, dark = 215, light = 245))).isEqualTo(ImageQualityGrade.LOW)
        assertThat(quality.of(flat(1600, 1200, 255))).isEqualTo(ImageQualityGrade.LOW)
    }

    @Test
    fun `a small image is low however sharp it is`() {
        assertThat(quality.of(checkerboard(400, 300, dark = 60, light = 200))).isEqualTo(ImageQualityGrade.LOW)
    }

    @Test
    fun `the short side is the one of the photo that was given and not of what is decoded`() {
        val image = checkerboard(1600, 1200, dark = 60, light = 200)

        assertThat(quality.measure(PhotoImage(image.bytes, 2000, 3000))!!.shortSide).isEqualTo(2000)
        assertThat(quality.measure(PhotoImage(image.bytes, 3000, 2000))!!.shortSide).isEqualTo(2000)
    }

    @Test
    fun `the measures of a sharp image and of a flat one are told apart`() {
        val sharp = quality.measure(checkerboard(1600, 1200, dark = 60, light = 200))!!
        val flat = quality.measure(flat(1600, 1200, 128))!!

        assertThat(sharp.sharpness).isGreaterThan(200.0)
        assertThat(sharp.meanBrightness).isBetween(120.0, 140.0)
        assertThat(sharp.extremeShare).isLessThan(0.10)
        assertThat(flat.sharpness).isLessThan(1.0)
        assertThat(flat.meanBrightness).isBetween(126.0, 130.0)
    }

    @Test
    fun `an image that cannot be read has no quality`() {
        assertThat(quality.of(PhotoImage("not a picture".toByteArray(), 1600, 1200))).isNull()
        assertThat(quality.measure(PhotoImage(ByteArray(0), 1600, 1200))).isNull()
    }

    @ParameterizedTest
    @CsvSource("0.0,LOW", "59.9,LOW", "60.0,NORMAL", "199.9,NORMAL", "200.0,GOOD", "5000.0,GOOD")
    fun `the sharpness is low below 60 and good from 200`(sharpness: Double, expected: ImageQualityGrade) {
        assertThat(quality.gradeOf(good().copy(sharpness = sharpness))).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "34.9,LOW",
        "35.0,NORMAL",
        "59.9,NORMAL",
        "60.0,GOOD",
        "200.0,GOOD",
        "200.1,NORMAL",
        "225.0,NORMAL",
        "225.1,LOW"
    )
    fun `the brightness is low outside 35 to 225 and good from 60 to 200`(
        brightness: Double,
        expected: ImageQualityGrade
    ) {
        assertThat(quality.gradeOf(good().copy(meanBrightness = brightness))).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource("0.0,GOOD", "0.0999,GOOD", "0.10,NORMAL", "0.40,NORMAL", "0.4001,LOW", "1.0,LOW")
    fun `the share of the ends of the scale is low above 40 percent and good below 10 percent`(
        share: Double,
        expected: ImageQualityGrade
    ) {
        assertThat(quality.gradeOf(good().copy(extremeShare = share))).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource("1,LOW", "479,LOW", "480,NORMAL", "1079,NORMAL", "1080,GOOD", "4000,GOOD")
    fun `the short side is low below 480 and good from 1080`(shortSide: Int, expected: ImageQualityGrade) {
        assertThat(quality.gradeOf(good().copy(shortSide = shortSide))).isEqualTo(expected)
    }

    @Test
    fun `one low condition makes the grade low even when everything else is good`() {
        assertThat(quality.gradeOf(good().copy(sharpness = 10.0))).isEqualTo(ImageQualityGrade.LOW)
        assertThat(quality.gradeOf(good().copy(shortSide = 100))).isEqualTo(ImageQualityGrade.LOW)
    }

    @Test
    fun `the numbers can be changed without a change of the code`() {
        val strict = ImageQuality(ImageQualityCriteria(lowShortSideBelow = 800, goodShortSideFrom = 1600))

        assertThat(strict.gradeOf(good().copy(shortSide = 700))).isEqualTo(ImageQualityGrade.LOW)
        assertThat(strict.gradeOf(good().copy(shortSide = 1500))).isEqualTo(ImageQualityGrade.NORMAL)
        assertThat(quality.gradeOf(good().copy(shortSide = 1500))).isEqualTo(ImageQualityGrade.GOOD)
    }

    @Test
    fun `the defaults are the table of the specification, a value for a value`() {
        val criteria = ImageQualityCriteria()

        assertThat(criteria.analysisLongSide).isEqualTo(1024)
        assertThat(criteria.darkPixelMax).isEqualTo(5)
        assertThat(criteria.brightPixelMin).isEqualTo(250)
        assertThat(criteria.lowSharpnessBelow).isEqualTo(60.0)
        assertThat(criteria.goodSharpnessFrom).isEqualTo(200.0)
        assertThat(criteria.lowMeanBrightnessBelow).isEqualTo(35.0)
        assertThat(criteria.lowMeanBrightnessAbove).isEqualTo(225.0)
        assertThat(criteria.lowExtremeShareAbove).isEqualTo(0.40)
        assertThat(criteria.goodMeanBrightnessFrom).isEqualTo(60.0)
        assertThat(criteria.goodMeanBrightnessTo).isEqualTo(200.0)
        assertThat(criteria.goodExtremeShareBelow).isEqualTo(0.10)
        assertThat(criteria.lowShortSideBelow).isEqualTo(480)
        assertThat(criteria.goodShortSideFrom).isEqualTo(1080)
    }

    @Test
    fun `numbers that cannot work together are refused`() {
        assertThatThrownBy { ImageQualityCriteria(lowSharpnessBelow = 300.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ImageQualityCriteria(goodMeanBrightnessFrom = 20.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ImageQualityCriteria(goodMeanBrightnessTo = 230.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ImageQualityCriteria(lowExtremeShareAbove = 0.05) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ImageQualityCriteria(lowExtremeShareAbove = 1.5) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ImageQualityCriteria(lowShortSideBelow = 2000) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ImageQualityCriteria(darkPixelMax = 250, brightPixelMin = 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ImageQualityCriteria(analysisLongSide = 2) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // A measure of which every number is a good one, so a test changes one number at a time.
    private fun good() = ImageQuality.Measure(
        sharpness = 500.0,
        meanBrightness = 130.0,
        extremeShare = 0.0,
        shortSide = 1200
    )

    private fun flat(width: Int, height: Int, grey: Int): PhotoImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(grey, grey, grey)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return photoOf(image)
    }

    // Squares of two greys, side by side: the edges between them are what makes the image sharp.
    private fun checkerboard(width: Int, height: Int, dark: Int, light: Int, cell: Int = 8): PhotoImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        for (y in 0 until height step cell) {
            for (x in 0 until width step cell) {
                val grey = if ((x / cell + y / cell) % 2 == 0) dark else light
                graphics.color = Color(grey, grey, grey)
                graphics.fillRect(x, y, cell, cell)
            }
        }
        graphics.dispose()
        return photoOf(image)
    }

    private fun photoOf(image: BufferedImage): PhotoImage {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        return PhotoImage(bytes, image.width, image.height)
    }
}
