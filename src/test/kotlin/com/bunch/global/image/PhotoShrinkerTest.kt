package com.bunch.global.image

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** A photo made small to be sent to the AI, with only its pixels left (recommended bundle specification 4-2). */
class PhotoShrinkerTest {
    @Test
    fun `a large photo gets its long side to the size asked for and keeps its shape`() {
        val shrunk = PhotoShrinker.shrink(image(3000, 2000, "jpg"), 1024)!!

        assertThat(shrunk.width).isEqualTo(1024)
        assertThat(shrunk.height).isEqualTo(683)
        assertThat(decoded(shrunk.bytes).width).isEqualTo(1024)
        assertThat(decoded(shrunk.bytes).height).isEqualTo(683)
    }

    @Test
    fun `a tall photo gets its height to the size asked for`() {
        val shrunk = PhotoShrinker.shrink(image(1500, 4000, "jpg"), 1024)!!

        assertThat(shrunk.height).isEqualTo(1024)
        assertThat(shrunk.width).isEqualTo(384)
    }

    @Test
    fun `a photo that is small enough is not made larger`() {
        val shrunk = PhotoShrinker.shrink(image(640, 480, "jpg"), 1024)!!

        assertThat(shrunk.width).isEqualTo(640)
        assertThat(shrunk.height).isEqualTo(480)
    }

    @Test
    fun `the result is a JPEG`() {
        val shrunk = PhotoShrinker.shrink(image(800, 600, "jpg"), 1024)!!

        assertThat(shrunk.bytes.take(2)).containsExactly(0xff.toByte(), 0xd8.toByte())
        assertThat(shrunk.bytes.takeLast(2)).containsExactly(0xff.toByte(), 0xd9.toByte())
    }

    @Test
    fun `an image with transparency is written as a JPEG too`() {
        val shrunk = PhotoShrinker.shrink(image(800, 600, "png", BufferedImage.TYPE_INT_ARGB), 1024)

        assertThat(shrunk).isNotNull
        assertThat(shrunk!!.bytes.take(2)).containsExactly(0xff.toByte(), 0xd8.toByte())
    }

    @Test
    fun `bytes that are not an image give nothing`() {
        assertThat(PhotoShrinker.shrink("not an image".toByteArray(), 1024)).isNull()
        assertThat(PhotoShrinker.shrink(ByteArray(0), 1024)).isNull()
    }

    @Test
    fun `a size that is not positive is refused`() {
        assertThatThrownBy { PhotoShrinker.shrink(image(10, 10, "jpg"), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun image(width: Int, height: Int, format: String, type: Int = BufferedImage.TYPE_INT_RGB): ByteArray {
        val image = BufferedImage(width, height, type)
        val graphics = image.createGraphics()
        graphics.color = Color(120, 160, 200)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    private fun decoded(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))
}
