package com.bunch.global.image

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class ImageFingerprintTest {
    @Test
    fun `the same image gives the same value`() {
        val first = ImageFingerprint.of(jpeg(scene(seed = 1)))
        val second = ImageFingerprint.of(jpeg(scene(seed = 1)))

        assertThat(first).isNotNull()
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `the same scene a bit brighter and saved again differs in only a few bits`() {
        val original = ImageFingerprint.of(jpeg(scene(seed = 2)))!!
        val brighter = ImageFingerprint.of(jpeg(scene(seed = 2, brighter = 6), quality = 0.7f))!!

        assertThat(distance(original, brighter)).isLessThanOrEqualTo(5)
    }

    @Test
    fun `the same scene shifted by a few pixels differs in only a few bits`() {
        val original = ImageFingerprint.of(jpeg(scene(seed = 3)))!!
        val shifted = ImageFingerprint.of(jpeg(scene(seed = 3, shift = 3)))!!

        assertThat(distance(original, shifted)).isLessThanOrEqualTo(5)
    }

    @Test
    fun `another scene differs in many bits`() {
        val first = ImageFingerprint.of(jpeg(scene(seed = 4)))!!

        val others = (5L..14L).map { ImageFingerprint.of(jpeg(scene(seed = it)))!! }

        assertThat(others.map { distance(first, it) }).allMatch { it > 12 }
    }

    @Test
    fun `an image of a single colour has a value`() {
        val plain = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                color = Color.GRAY
                fillRect(0, 0, 200, 100)
                dispose()
            }
        }

        assertThat(ImageFingerprint.of(jpeg(plain))).isNotNull()
    }

    @Test
    fun `an image smaller than the grid has a value`() {
        val tiny = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)

        assertThat(ImageFingerprint.of(png(tiny))).isNotNull()
    }

    @Test
    fun `a large image is read at a reduced size and has a value`() {
        val large = scene(seed = 6, width = 4000, height = 3000)

        assertThat(ImageFingerprint.of(jpeg(large))).isNotNull()
    }

    @Test
    fun `bytes that are not an image have no value`() {
        assertThat(ImageFingerprint.of("not an image".toByteArray())).isNull()
        assertThat(ImageFingerprint.of(ByteArray(0))).isNull()
    }

    @Test
    fun `a cut off image has no value or one that does not fail`() {
        val whole = jpeg(scene(seed = 7))

        assertThat(runCatching { ImageFingerprint.of(whole.copyOf(whole.size / 3)) }.isSuccess).isTrue()
    }

    // Rectangles of random greys, the same for the same seed. A shift moves them, a brighter scene adds to each grey.
    private fun scene(
        seed: Long,
        brighter: Int = 0,
        shift: Int = 0,
        width: Int = 640,
        height: Int = 480
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val random = Random(seed)
        val graphics = image.createGraphics()
        graphics.color = Color(120, 120, 120)
        graphics.fillRect(0, 0, width, height)
        repeat(40) {
            val grey = (random.nextInt(200) + 20 + brighter).coerceIn(0, 255)
            graphics.color = Color(grey, grey, grey)
            val x = random.nextInt(width - 60)
            val y = random.nextInt(height - 60)
            graphics.fillRect(x + shift, y + shift, 40 + random.nextInt(120), 40 + random.nextInt(120))
        }
        graphics.dispose()
        return image
    }

    private fun jpeg(image: BufferedImage, quality: Float = 0.9f): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    private fun distance(first: Long, second: Long): Int = java.lang.Long.bitCount(first xor second)
}
