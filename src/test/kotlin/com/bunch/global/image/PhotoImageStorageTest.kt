package com.bunch.global.image

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.ISO_8859_1
import javax.imageio.ImageIO

class PhotoImageStorageTest {
    private val storage = PhotoImageStorage()

    @Test
    fun `accepts a JPEG and keeps the uploaded bytes untouched`() {
        val source = jpeg(1200, 800)

        val image = storage.inspect(file(source))

        assertArrayEquals(source, image.bytes)
        assertEquals(1200, image.width)
        assertEquals(800, image.height)
    }

    @Test
    fun `reports display dimensions for all EXIF orientations without changing original bytes`() {
        for (orientation in 1..8) {
            val exif = byteArrayOf(
                0x45, 0x78, 0x69, 0x66, 0, 0,
                0x49, 0x49, 0x2a, 0, 8, 0, 0, 0,
                1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0,
                orientation.toByte(), 0, 0, 0, 0, 0, 0, 0
            )
            val bytes = withSegment(jpeg(120, 80), 0xe1, segmentLength(exif.size + 2) + exif)

            val image = storage.inspect(file(bytes))

            assertEquals(if (orientation >= 5) 80 else 120, image.width, "orientation=$orientation")
            assertEquals(if (orientation >= 5) 120 else 80, image.height, "orientation=$orientation")
            assertArrayEquals(bytes, image.bytes)
        }
    }

    @Test
    fun `does not require a square image and allows the long side limit exactly`() {
        assertDoesNotThrow { storage.inspect(file(jpeg(4096, 1))) }
        assertDoesNotThrow { storage.inspect(file(jpeg(1, 4096))) }
        assertDoesNotThrow { storage.inspect(file(jpeg(2560, 1707))) }
    }

    @Test
    fun `rejects a long side over the limit from the header alone`() {
        assertRejected(PhotoImageRejection.RESOLUTION_EXCEEDED, file(jpeg(4097, 1)))
        assertRejected(PhotoImageRejection.RESOLUTION_EXCEEDED, file(jpeg(1, 4097)))
        val hugeHeader = jpeg(8, 8).copyOf()
        val sof = (0 until hugeHeader.size - 1).first {
            hugeHeader[it] == 0xff.toByte() && hugeHeader[it + 1] == 0xc0.toByte()
        }
        (5..8).forEach { hugeHeader[sof + it] = 0xff.toByte() }
        assertRejected(PhotoImageRejection.RESOLUTION_EXCEEDED, file(hugeHeader))
    }

    @Test
    fun `the resolution limit is configurable`() {
        val small = PhotoImageStorage(maxDimension = 2560)

        assertDoesNotThrow { small.inspect(file(jpeg(2560, 10))) }
        assertThrows(PhotoImageRejectedException::class.java) { small.inspect(file(jpeg(2561, 10))) }
    }

    @Test
    fun `rejects files over thirty MiB even when the declared size lies`() {
        val padded = jpeg().copyOf(THIRTY_MIB + 1)
        val lying = object : MockMultipartFile("photo", "image.jpg", "image/jpeg", padded) {
            override fun getSize() = 1L
        }

        assertRejected(PhotoImageRejection.TOO_LARGE, file(padded))
        assertRejected(PhotoImageRejection.TOO_LARGE, lying)
    }

    @Test
    fun `accepts a JPEG of exactly thirty MiB`() {
        val exact = paddedTo(jpeg(), THIRTY_MIB)
        assertEquals(THIRTY_MIB, exact.size)

        assertEquals(64, storage.inspect(file(exact)).width)
    }

    @Test
    fun `rejects other formats wrong content types and broken files`() {
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val valid = jpeg()

        assertRejected(PhotoImageRejection.UNSUPPORTED_FORMAT, file(png))
        assertRejected(PhotoImageRejection.UNSUPPORTED_FORMAT, file(valid, "image/png"))
        assertRejected(PhotoImageRejection.UNSUPPORTED_FORMAT, file(valid, null))
        assertRejected(PhotoImageRejection.UNSUPPORTED_FORMAT, file(byteArrayOf()))
        assertRejected(PhotoImageRejection.UNSUPPORTED_FORMAT, file(valid.copyOf(valid.size / 2)))
        assertRejected(PhotoImageRejection.UNSUPPORTED_FORMAT, file(valid + byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `rejects images that still carry location metadata`() {
        val valid = jpeg()

        assertRejected(PhotoImageRejection.LOCATION_METADATA, file(withSegment(valid, 0xe1, gpsExif())))
        assertRejected(PhotoImageRejection.LOCATION_METADATA, file(withSegment(valid, 0xe1, xmpWithGps())))
    }

    @Test
    fun `rejects location metadata placed after the scan data`() {
        val valid = jpeg()

        assertRejected(PhotoImageRejection.LOCATION_METADATA, file(withSegmentAfterScan(valid, 0xe1, gpsExif())))
        assertRejected(PhotoImageRejection.LOCATION_METADATA, file(withSegmentAfterScan(valid, 0xe1, xmpWithGps())))
    }

    private fun assertRejected(reason: PhotoImageRejection, file: MockMultipartFile) {
        val error = assertThrows(PhotoImageRejectedException::class.java) { storage.inspect(file) }
        assertEquals(reason, error.reason)
        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
    }

    private fun file(bytes: ByteArray, contentType: String? = "image/jpeg") =
        MockMultipartFile("photo", "untrusted-name.jpg", contentType, bytes)

    private fun jpeg(width: Int = 64, height: Int = 64): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", it)
    }.toByteArray()

    private fun withSegment(jpeg: ByteArray, marker: Int, body: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), marker.toByte()) + body + jpeg.copyOfRange(2, jpeg.size)

    private fun withSegmentAfterScan(jpeg: ByteArray, marker: Int, body: ByteArray): ByteArray =
        jpeg.copyOfRange(0, jpeg.size - 2) + byteArrayOf(0xff.toByte(), marker.toByte()) + body +
            byteArrayOf(0xff.toByte(), 0xd9.toByte())

    // A JPEG segment holds at most 65533 bytes, so the padding is spread over many comment segments.
    private fun paddedTo(valid: ByteArray, total: Int): ByteArray {
        val out = ByteArrayOutputStream(total)
        out.write(valid, 0, 2)
        var remaining = total - valid.size
        while (remaining > 0) {
            val cost = if (remaining > COMMENT_COST + 4) COMMENT_COST else remaining
            out.write(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
            out.write(segmentLength(cost - 2))
            out.write(ByteArray(cost - 4))
            remaining -= cost
        }
        out.write(valid, 2, valid.size - 2)
        return out.toByteArray()
    }

    private fun segmentLength(length: Int) = byteArrayOf((length shr 8).toByte(), length.toByte())

    private fun gpsExif(): ByteArray {
        val tiff = "Exif".toByteArray(ISO_8859_1) + byteArrayOf(0, 0) + "II".toByteArray() +
            byteArrayOf(42, 0, 8, 0, 0, 0, 1, 0, 0x25, 0x88.toByte(), 4, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        return segmentLength(tiff.size + 2) + tiff
    }

    private fun xmpWithGps(): ByteArray {
        val packet = (
            "http://ns.adobe.com/xap/1.0/\u0000<x xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" " +
                "exif:GPSLatitude=\"37\"/>"
            ).toByteArray(ISO_8859_1)
        return segmentLength(packet.size + 2) + packet
    }

    private companion object {
        const val THIRTY_MIB = 30 * 1024 * 1024
        const val COMMENT_COST = 1004
    }
}
