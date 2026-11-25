package com.bunch.global.image

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class PhotoThumbnailTest {
    @Test
    fun `applies every EXIF orientation to asymmetric pixels`() {
        val source = quadrantImage()
        val expected = listOf(
            listOf(RED, GREEN, BLUE, YELLOW),
            listOf(GREEN, RED, YELLOW, BLUE),
            listOf(YELLOW, BLUE, GREEN, RED),
            listOf(BLUE, YELLOW, RED, GREEN),
            listOf(RED, BLUE, GREEN, YELLOW),
            listOf(BLUE, RED, YELLOW, GREEN),
            listOf(YELLOW, GREEN, BLUE, RED),
            listOf(GREEN, YELLOW, RED, BLUE)
        )

        (1..8).forEach { orientation ->
            val input = withExifOrientation(jpeg(source), orientation, littleEndian = orientation % 2 == 1)
            val output = decoded(PhotoThumbnail.create(input))

            assertEquals(if (orientation >= 5) 40 else 80, output.width, "orientation=$orientation width")
            assertEquals(if (orientation >= 5) 80 else 40, output.height, "orientation=$orientation height")
            listOf(
                output.width / 4 to output.height / 4,
                output.width * 3 / 4 to output.height / 4,
                output.width / 4 to output.height * 3 / 4,
                output.width * 3 / 4 to output.height * 3 / 4
            ).zip(expected[orientation - 1]).forEach { (point, color) ->
                assertColorNear(output.getRGB(point.first, point.second), color, orientation)
            }
        }
    }

    @Test
    fun `defaults to orientation one when EXIF is absent`() {
        val output = decoded(PhotoThumbnail.create(jpeg(quadrantImage())))

        assertColorNear(output.getRGB(10, 10), RED, 1)
        assertColorNear(output.getRGB(70, 10), GREEN, 1)
        assertColorNear(output.getRGB(10, 30), BLUE, 1)
        assertColorNear(output.getRGB(70, 30), YELLOW, 1)
    }

    @Test
    fun `reads EXIF orientation stored after the scan`() {
        val input = withExifPayloadAfterScan(
            jpeg(quadrantImage()),
            exifOrientationPayload(6, littleEndian = true)
        )
        val output = decoded(PhotoThumbnail.create(input))

        assertColorNear(output.getRGB(10, 10), BLUE, 6)
        assertColorNear(output.getRGB(30, 10), RED, 6)
        assertColorNear(output.getRGB(10, 70), YELLOW, 6)
        assertColorNear(output.getRGB(30, 70), GREEN, 6)
    }

    @Test
    fun `fits the full source within 1080 preserving aspect ratio without upscaling`() {
        assertEquals(1 to 1, thumbnailDimensions(image(1, 1)))
        assertEquals(80 to 40, thumbnailDimensions(image(80, 40)))
        assertEquals(400 to 800, thumbnailDimensions(image(400, 800)))
        assertEquals(1080 to 540, thumbnailDimensions(image(4096, 2048)))
        assertEquals(810 to 1080, thumbnailDimensions(image(1920, 2560)))
        assertEquals(1080 to 720, thumbnailDimensions(image(4000, 2667)))
        assertEquals(1 to 1080, thumbnailDimensions(image(1, 4096)))
        assertEquals(1080 to 1, thumbnailDimensions(image(4096, 1)))
        assertEquals(1080 to 1080, thumbnailDimensions(image(4096, 4096)))
    }

    @Test
    fun `keeps both outer edges rather than cropping the source`() {
        val source = image(2400, 1200).apply {
            createGraphics().also { graphics ->
                graphics.color = Color(RED)
                graphics.fillRect(0, 0, 600, height)
                graphics.color = Color(GREEN)
                graphics.fillRect(600, 0, 1200, height)
                graphics.color = Color(BLUE)
                graphics.fillRect(1800, 0, 600, height)
                graphics.dispose()
            }
        }
        val output = decoded(PhotoThumbnail.create(jpeg(source)))

        assertEquals(1080 to 540, output.width to output.height)
        assertColorNear(output.getRGB(50, 270), RED, 1)
        assertColorNear(output.getRGB(540, 270), GREEN, 1)
        assertColorNear(output.getRGB(1030, 270), BLUE, 1)
    }

    @Test
    fun `accepts the exact input limit and rejects bytes beyond it`() {
        val exact = paddedTo(jpeg(image(1, 1)), MAX_INPUT_BYTES)

        assertEquals(MAX_INPUT_BYTES, exact.size)
        assertEquals(1 to 1, dimensions(PhotoThumbnail.create(exact)))
        assertThrows(IllegalArgumentException::class.java) {
            PhotoThumbnail.create(ByteArray(MAX_INPUT_BYTES + 1))
        }
    }

    @Test
    fun `checks the 4096 dimension limit before decoding pixels`() {
        val oversizedHeader = patchDimensions(jpeg(image(8, 8)), 4097, 4097)

        assertThrows(IllegalArgumentException::class.java) {
            PhotoThumbnail.create(oversizedHeader)
        }
    }

    @Test
    fun `rejects corrupt, truncated, concatenated, and trailing JPEG data`() {
        val valid = jpeg(image(40, 40))
        val earlyEnd = valid.copyOf(valid.size / 2) + byteArrayOf(0xff.toByte(), 0xd9.toByte())

        listOf(
            valid.copyOf(valid.size - 2),
            earlyEnd,
            valid + byteArrayOf(1, 2, 3, 0xff.toByte(), 0xd9.toByte()),
            valid + valid,
            byteArrayOf(0, 1, 2, 3)
        ).forEach { corrupt ->
            assertThrows(IllegalArgumentException::class.java) {
                PhotoThumbnail.create(corrupt)
            }
        }
    }

    @Test
    fun `rejects a tall JPEG truncated near the end of the frame`() {
        val valid = jpeg(tallImage())
        val truncated = truncateScan(valid, 0.70)

        assertThrows(IllegalArgumentException::class.java) {
            PhotoThumbnail.create(truncated)
        }
    }

    @Test
    fun `rejects malformed and out of range EXIF orientation`() {
        val source = jpeg(image(40, 40))
        val malformed = withExifPayload(source, "Exif\u0000\u0000broken".toByteArray())
        val invalidOrientation = withExifOrientation(source, 9, littleEndian = true)

        assertThrows(IllegalArgumentException::class.java) { PhotoThumbnail.create(malformed) }
        assertThrows(IllegalArgumentException::class.java) { PhotoThumbnail.create(invalidOrientation) }
    }

    @Test
    fun `limiter permits at most two actions and releases permits after failure`() {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val futures = (1..8).map {
            executor.submit {
                start.await()
                ImageDecodeLimiter.withPermit {
                    val current = active.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, current) }
                    try {
                        Thread.sleep(60)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }

        start.countDown()
        try {
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertTrue(maximum.get() <= 2, "maximum active actions=${maximum.get()}")

        assertThrows(IllegalStateException::class.java) {
            ImageDecodeLimiter.withPermit { throw IllegalStateException("test failure") }
        }
        assertDoesNotThrow { ImageDecodeLimiter.withPermit { 42 } }
    }

    private fun quadrantImage(): BufferedImage = BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().also { graphics ->
            graphics.color = Color(RED)
            graphics.fillRect(0, 0, 40, 20)
            graphics.color = Color(GREEN)
            graphics.fillRect(40, 0, 40, 20)
            graphics.color = Color(BLUE)
            graphics.fillRect(0, 20, 40, 20)
            graphics.color = Color(YELLOW)
            graphics.fillRect(40, 20, 40, 20)
            graphics.dispose()
        }
    }

    private fun image(width: Int, height: Int): BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

    private fun tallImage(): BufferedImage = image(512, 2048).apply {
        val random = Random(213L)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val red = random.nextInt(256)
                val green = (red + x + y) and 0xff
                val blue = (green + x * 3 + y * 5) and 0xff
                setRGB(x, y, (red shl 16) or (green shl 8) or blue)
            }
        }
    }

    private fun decoded(bytes: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(bytes)) ?: error("thumbnail is not a decodable JPEG")

    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val image = decoded(bytes)
        return image.width to image.height
    }

    private fun thumbnailDimensions(source: BufferedImage): Pair<Int, Int> =
        dimensions(PhotoThumbnail.create(jpeg(source)))

    private fun jpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                val parameter = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 1.0f
                }
                writer.write(null, IIOImage(image, null, null), parameter)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    private fun withExifOrientation(bytes: ByteArray, orientation: Int, littleEndian: Boolean): ByteArray {
        return withExifPayload(bytes, exifOrientationPayload(orientation, littleEndian))
    }

    private fun exifOrientationPayload(orientation: Int, littleEndian: Boolean): ByteArray {
        val tiff = byteArrayOutputStream {
            val byteOrder = if (littleEndian) {
                byteArrayOf('I'.code.toByte(), 'I'.code.toByte())
            } else {
                byteArrayOf('M'.code.toByte(), 'M'.code.toByte())
            }
            write(byteOrder)
            write(u16(42, littleEndian))
            write(u32(8, littleEndian))
            write(u16(1, littleEndian))
            write(u16(0x0112, littleEndian))
            write(u16(3, littleEndian))
            write(u32(1, littleEndian))
            write(u16(orientation, littleEndian))
            write(u16(0, littleEndian))
            write(u32(0, littleEndian))
        }
        return "Exif\u0000\u0000".toByteArray() + tiff
    }

    private fun withExifPayload(bytes: ByteArray, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return bytes.copyOfRange(0, 2) + byteArrayOf(
            0xff.toByte(), 0xe1.toByte(),
            (length ushr 8).toByte(), length.toByte()
        ) + payload + bytes.copyOfRange(2, bytes.size)
    }

    private fun withExifPayloadAfterScan(bytes: ByteArray, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return bytes.copyOfRange(0, bytes.size - 2) + byteArrayOf(
            0xff.toByte(), 0xe1.toByte(),
            (length ushr 8).toByte(), length.toByte()
        ) + payload + byteArrayOf(0xff.toByte(), 0xd9.toByte())
    }

    private fun patchDimensions(bytes: ByteArray, width: Int, height: Int): ByteArray {
        val patched = bytes.copyOf()
        val marker = (0 until patched.size - 1).first { index ->
            (patched[index].toInt() and 0xff) == 0xff &&
                (patched[index + 1].toInt() and 0xff) in 0xc0..0xcf &&
                (patched[index + 1].toInt() and 0xff) !in setOf(0xc4, 0xc8, 0xcc)
        }
        patched[marker + 5] = (height ushr 8).toByte()
        patched[marker + 6] = height.toByte()
        patched[marker + 7] = (width ushr 8).toByte()
        patched[marker + 8] = width.toByte()
        return patched
    }

    private fun truncateScan(bytes: ByteArray, fraction: Double): ByteArray {
        val sos = (0 until bytes.size - 1).first { index ->
            (bytes[index].toInt() and 0xff) == 0xff &&
                (bytes[index + 1].toInt() and 0xff) == 0xda
        }
        val segmentLength = ((bytes[sos + 2].toInt() and 0xff) shl 8) or
            (bytes[sos + 3].toInt() and 0xff)
        val scanStart = sos + 2 + segmentLength
        val scanEnd = bytes.size - 2
        val cut = scanStart + ((scanEnd - scanStart) * fraction).toInt()
        return bytes.copyOf(cut) + byteArrayOf(0xff.toByte(), 0xd9.toByte())
    }

    private fun paddedTo(bytes: ByteArray, target: Int): ByteArray {
        require(target > bytes.size)
        val output = ByteArrayOutputStream(target)
        output.write(bytes, 0, 2)
        var remaining = target - bytes.size
        val count = (remaining + MAX_COMMENT_COST - 1) / MAX_COMMENT_COST
        repeat(count) { index ->
            val segmentsAfter = count - index - 1
            val cost = minOf(MAX_COMMENT_COST, remaining - segmentsAfter * MIN_COMMENT_COST)
            require(cost in MIN_COMMENT_COST..MAX_COMMENT_COST)
            output.write(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
            output.write(byteArrayOf((cost - 2 ushr 8).toByte(), (cost - 2).toByte()))
            output.write(ByteArray(cost - 4))
            remaining -= cost
        }
        output.write(bytes, 2, bytes.size - 2)
        return output.toByteArray()
    }

    private fun byteArrayOutputStream(block: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply(block).toByteArray()

    private fun u16(value: Int, littleEndian: Boolean): ByteArray = if (littleEndian) {
        byteArrayOf(value.toByte(), (value ushr 8).toByte())
    } else {
        byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun u32(value: Int, littleEndian: Boolean): ByteArray = if (littleEndian) {
        u16(value, true) + u16(0, true)
    } else {
        u16(0, false) + u16(value, false)
    }

    private fun assertColorNear(actual: Int, expected: Int, orientation: Int) {
        val red = actual ushr 16 and 0xff
        val green = actual ushr 8 and 0xff
        val blue = actual and 0xff
        val expectedRed = expected ushr 16 and 0xff
        val expectedGreen = expected ushr 8 and 0xff
        val expectedBlue = expected and 0xff
        val distance = maxOf(
            kotlin.math.abs(red - expectedRed),
            kotlin.math.abs(green - expectedGreen),
            kotlin.math.abs(blue - expectedBlue)
        )
        assertTrue(distance <= 80, "orientation=$orientation actual=#$red$green$blue expected=$expected")
    }

    private companion object {
        const val RED = 0x00f01e1e
        const val GREEN = 0x001edc1e
        const val BLUE = 0x001e3cf0
        const val YELLOW = 0x00f0dc1e
        const val MAX_INPUT_BYTES = 30 * 1024 * 1024
        const val MIN_COMMENT_COST = 4
        const val MAX_COMMENT_COST = 65_537
    }
}
