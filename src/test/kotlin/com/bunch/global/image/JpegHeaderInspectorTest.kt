package com.bunch.global.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.ISO_8859_1
import javax.imageio.ImageIO

class JpegHeaderInspectorTest {
    @Test
    fun `reads declared dimensions without decoding image data`() {
        val header = JpegHeaderInspector.inspect(jpeg(64, 32))

        assertEquals(64, header.width)
        assertEquals(32, header.height)
        assertFalse(header.hasLocationMetadata)
    }

    @Test
    fun `reports a huge declared raster from a tiny file`() {
        val tiny = jpeg(8, 8).copyOf()
        val sof = sofOffset(tiny)
        // height and width are two big-endian bytes each after the length and precision bytes.
        tiny[sof + 5] = 0xff.toByte()
        tiny[sof + 6] = 0xff.toByte()
        tiny[sof + 7] = 0xff.toByte()
        tiny[sof + 8] = 0xff.toByte()

        val header = JpegHeaderInspector.inspect(tiny)

        assertEquals(65535, header.width)
        assertEquals(65535, header.height)
    }

    @Test
    fun `detects the GPS pointer in little and big endian EXIF`() {
        listOf(true, false).forEach { little ->
            val header = JpegHeaderInspector.inspect(withSegment(jpeg(), 0xe1, exif(little, listOf(0x8825))))
            assertTrue(header.hasLocationMetadata, "little=$little")
        }
    }

    @Test
    fun `accepts EXIF that only carries orientation and capture time pointers`() {
        // 0x0112 Orientation and 0x8769 Exif IFD pointer are allowed. Only the GPS pointer 0x8825 is not.
        listOf(true, false).forEach { little ->
            val header = JpegHeaderInspector.inspect(withSegment(jpeg(), 0xe1, exif(little, listOf(0x0112, 0x8769))))
            assertFalse(header.hasLocationMetadata, "little=$little")
        }
    }

    @Test
    fun `detects location properties in XMP and ignores unrelated XMP`() {
        val gps = xmp("""<rdf:Description exif:GPSLatitude="37,30.0N" exif:GPSLongitude="127,2.0E"/>""")
        val city = xmp("""<rdf:Description photoshop:City="Seoul"/>""")
        val rating = xmp("""<rdf:Description xmp:Rating="5"/>""")

        assertTrue(JpegHeaderInspector.inspect(withSegment(jpeg(), 0xe1, gps)).hasLocationMetadata)
        assertTrue(JpegHeaderInspector.inspect(withSegment(jpeg(), 0xe1, city)).hasLocationMetadata)
        assertFalse(JpegHeaderInspector.inspect(withSegment(jpeg(), 0xe1, rating)).hasLocationMetadata)
    }

    @Test
    fun `finds metadata that appears after other segments`() {
        val comment = segmentBody("harmless".toByteArray())
        val image = withSegment(withSegment(jpeg(), 0xfe, comment), 0xe1, exif(true, listOf(0x8825)))

        assertTrue(JpegHeaderInspector.inspect(image).hasLocationMetadata)
    }

    @Test
    fun `rejects malformed structure instead of guessing`() {
        val valid = jpeg()
        val brokenExif = withSegment(
            valid,
            0xe1,
            segmentBody(exifHeader(true) + byteArrayOf(0x2a, 0, 0xff.toByte(), 0, 0, 0))
        )
        val cases = listOf(
            "too short" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
            "no SOI" to byteArrayOf(1, 2, 3, 4, 5),
            "image ends before a frame header" to
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
            "segment longer than the file" to valid.copyOf(20),
            "EXIF IFD offset beyond the segment" to brokenExif
        )

        cases.forEach { (name, bytes) ->
            assertThrows(IllegalArgumentException::class.java, { JpegHeaderInspector.inspect(bytes) }, name)
        }
    }

    private fun jpeg(width: Int = 64, height: Int = 64): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", it)
    }.toByteArray()

    private fun sofOffset(bytes: ByteArray): Int = (0 until bytes.size - 1).first {
        bytes[it] == 0xff.toByte() && bytes[it + 1] == 0xc0.toByte()
    }

    private fun withSegment(jpeg: ByteArray, marker: Int, body: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), marker.toByte()) + body + jpeg.copyOfRange(2, jpeg.size)

    // A segment body starts with its two byte length, which counts itself.
    private fun segmentBody(data: ByteArray): ByteArray {
        val length = data.size + 2
        return byteArrayOf((length shr 8).toByte(), length.toByte()) + data
    }

    private fun exifHeader(little: Boolean): ByteArray =
        "Exif".toByteArray(ISO_8859_1) + byteArrayOf(0, 0) + if (little) "II".toByteArray() else "MM".toByteArray()

    private fun exif(little: Boolean, tags: List<Int>): ByteArray {
        fun u16(value: Int) = if (little) byteArrayOf(value.toByte(), (value shr 8).toByte())
        else byteArrayOf((value shr 8).toByte(), value.toByte())

        fun u32(value: Int) = if (little) u16(value) + u16(0) else u16(0) + u16(value)
        var data = exifHeader(little) + u16(42) + u32(8) + u16(tags.size)
        tags.forEach { data += u16(it) + u16(3) + u32(1) + u32(0) }
        return segmentBody(data + u32(0))
    }

    private fun xmp(rdf: String): ByteArray =
        segmentBody(("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>$rdf</x:xmpmeta>").toByteArray(ISO_8859_1))
}
