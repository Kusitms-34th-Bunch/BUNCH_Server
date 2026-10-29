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
    fun `matches XMP location properties by namespace whatever prefix is used`() {
        val renamedAttribute = xmp(
            """<rdf:Description e:GPSLatitude="37"/>""",
            "xmlns:e=\"$EXIF_NAMESPACE\""
        )
        val element = xmp("""<rdf:Description><exif:GPSLongitude>127</exif:GPSLongitude></rdf:Description>""")
        val renamedCity = xmp(
            """<rdf:Description p:City="Seoul"/>""",
            "xmlns:p=\"http://ns.adobe.com/photoshop/1.0/\""
        )
        val nested = xmp("<rdf:Description>" + "<rdf:Bag>".repeat(500) + """<rdf:li exif:GPSAltitude="1"/>""" +
            "</rdf:Bag>".repeat(500) + "</rdf:Description>")

        listOf(renamedAttribute, element, renamedCity, nested).forEachIndexed { index, segment ->
            assertTrue(JpegHeaderInspector.inspect(withSegment(jpeg(), 0xe1, segment)).hasLocationMetadata, "$index")
        }
    }

    @Test
    fun `does not treat a prefix bound to another namespace as location`() {
        val foreign = xmp(
            """<rdf:Description exif:GPSLatitude="37" photoshop:City="Seoul"/>""",
            "xmlns:exif=\"http://example.com/other\" xmlns:photoshop=\"http://example.com/other2\""
        )

        assertFalse(JpegHeaderInspector.inspect(withSegment(jpeg(), 0xe1, foreign)).hasLocationMetadata)
    }

    @Test
    fun `rejects XMP that is not well-formed or declares a DOCTYPE`() {
        val broken = xmpPacket("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF>")
        val unbound = xmpPacket("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><exif:GPSLatitude/></x:xmpmeta>")
        val doctype = xmpPacket("<!DOCTYPE x [<!ENTITY a \"b\">]><x>&a;</x>")

        listOf(broken, unbound, doctype).forEachIndexed { index, segment ->
            assertThrows(
                IllegalArgumentException::class.java,
                { JpegHeaderInspector.inspect(withSegment(jpeg(), 0xe1, segment)) },
                "$index"
            )
        }
    }

    @Test
    fun `finds metadata that appears after other segments`() {
        val comment = segmentBody("harmless".toByteArray())
        val image = withSegment(withSegment(jpeg(), 0xfe, comment), 0xe1, exif(true, listOf(0x8825)))

        assertTrue(JpegHeaderInspector.inspect(image).hasLocationMetadata)
    }

    @Test
    fun `detects location metadata placed after the scan data`() {
        val exifAfterScan = withSegmentAfterScan(jpeg(), 0xe1, exif(true, listOf(0x8825)))
        val xmpAfterScan = withSegmentAfterScan(jpeg(), 0xe1, xmp("""<rdf:Description exif:GPSLatitude="1"/>"""))
        val rating = withSegmentAfterScan(jpeg(), 0xe1, xmp("""<rdf:Description xmp:Rating="5"/>"""))

        assertTrue(JpegHeaderInspector.inspect(exifAfterScan).hasLocationMetadata)
        assertTrue(JpegHeaderInspector.inspect(xmpAfterScan).hasLocationMetadata)
        assertFalse(JpegHeaderInspector.inspect(rating).hasLocationMetadata)
    }

    @Test
    fun `does not mistake stuffed bytes inside scan data for markers`() {
        val noisy = noisyJpeg()
        // The scan holds ff00 pairs; treating one as a marker would reject this valid file.
        assertTrue(noisy.indices.any { noisy[it] == 0xff.toByte() && noisy.getOrNull(it + 1) == 0.toByte() })

        val header = JpegHeaderInspector.inspect(withSegmentAfterScan(noisy, 0xe1, exif(false, listOf(0x8825))))

        assertEquals(128, header.width)
        assertTrue(header.hasLocationMetadata)
        assertFalse(JpegHeaderInspector.inspect(noisy).hasLocationMetadata)
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
            "EXIF IFD offset beyond the segment" to brokenExif,
            "scan data ends without EOI" to valid.copyOf(valid.size - 2),
            "segment after the scan longer than the file" to
                valid.copyOf(valid.size - 2) + bytesOf(0xff, 0xe1, 0x7f, 0xff, 0xff, 0xd9)
        )

        cases.forEach { (name, bytes) ->
            assertThrows(IllegalArgumentException::class.java, { JpegHeaderInspector.inspect(bytes) }, name)
        }
    }

    private fun jpeg(width: Int = 64, height: Int = 64): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", it)
    }.toByteArray()

    // Random pixels make the entropy-coded data long and full of 0xff bytes, which the encoder stuffs as ff00.
    private fun noisyJpeg(): ByteArray {
        val random = java.util.Random(7)
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 128) for (y in 0 until 128) image.setRGB(x, y, random.nextInt(0x1000000))
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }

    private fun sofOffset(bytes: ByteArray): Int = (0 until bytes.size - 1).first {
        bytes[it] == 0xff.toByte() && bytes[it + 1] == 0xc0.toByte()
    }

    private fun withSegment(jpeg: ByteArray, marker: Int, body: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), marker.toByte()) + body + jpeg.copyOfRange(2, jpeg.size)

    private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    // Puts the segment between the last scan and the closing EOI.
    private fun withSegmentAfterScan(jpeg: ByteArray, marker: Int, body: ByteArray): ByteArray =
        jpeg.copyOfRange(0, jpeg.size - 2) + byteArrayOf(0xff.toByte(), marker.toByte()) + body +
            byteArrayOf(0xff.toByte(), 0xd9.toByte())

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

    private fun xmp(rdf: String, namespaces: String = XMP_NAMESPACES): ByteArray =
        xmpPacket(
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"$RDF_NAMESPACE\" $namespaces>" +
                "$rdf</rdf:RDF></x:xmpmeta>"
        )

    private fun xmpPacket(xml: String): ByteArray =
        segmentBody(("http://ns.adobe.com/xap/1.0/\u0000$xml").toByteArray(ISO_8859_1))

    private companion object {
        const val RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        const val EXIF_NAMESPACE = "http://ns.adobe.com/exif/1.0/"
        const val XMP_NAMESPACES = "xmlns:exif=\"$EXIF_NAMESPACE\" " +
            "xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\""
    }
}
