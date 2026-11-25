package com.bunch.global.image

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.ISO_8859_1
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

class ProfileImageStorageTest {
    private lateinit var stub: R2TestServer
    private lateinit var storage: ProfileImageStorage

    @BeforeEach
    fun setup() {
        stub = R2TestServer()
        storage = ProfileImageStorage(stub.storage)
    }

    @AfterEach
    fun teardown() { stub.close() }

    @Test
    fun `reencoding removes EXIF orientation and JPEG comments`() {
        val exif = byteArrayOf(
            0x45, 0x78, 0x69, 0x66, 0, 0, 0x49, 0x49, 0x2a, 0, 8, 0, 0, 0,
            1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0
        )
        val comment = "private-test-comment".toByteArray()
        val original = jpeg()
        val metadataImage = original.copyOfRange(0, 2) + segment(0xe1, exif) +
            segment(0xfe, comment) + original.copyOfRange(2, original.size)

        storage.upload(file(metadataImage))

        val sanitized = stub.requests.single { it.method == "PUT" }.body
        assertFalse(String(sanitized, ISO_8859_1).contains("Exif"))
        assertFalse(String(sanitized, ISO_8859_1).contains("private-test-comment"))
        MemoryCacheImageInputStream(ByteArrayInputStream(sanitized)).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            try {
                reader.input = input
                val metadata = reader.getImageMetadata(0).getAsTree("javax_imageio_jpeg_image_1.0")
                fun hasPrivateMetadata(node: org.w3c.dom.Node): Boolean =
                    node.nodeName in setOf("unknown", "com") ||
                        (0 until node.childNodes.length).any { hasPrivateMetadata(node.childNodes.item(it)) }
                assertFalse(hasPrivateMetadata(metadata))
                assertEquals(64, reader.read(0).width)
            } finally { reader.dispose() }
        }
    }

    @Test
    fun `1024 square boundary and one pixel square are accepted`() {
        storage.upload(file(jpeg(1024, 1024)))
        storage.upload(file(jpeg(1, 1)))
        assertEquals(2, stub.requests.size)
    }

    @Test
    fun `rejects PNG disguised as JPEG and non JPEG content types`() {
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        rejected(file(png))
        rejected(file(jpeg(), "image/png"))
        rejected(file(jpeg(), null))
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `rejects empty non-square oversized dimensions and files over five MiB`() {
        rejected(file(byteArrayOf()))
        rejected(file(jpeg(64, 32)))
        rejected(file(jpeg(1025, 1025)))
        rejected(file(ByteArray(5 * 1024 * 1024 + 1)))
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `checks declared JPEG dimensions before trying to decode malicious image data`() {
        val oversizedHeader = jpeg().copyOf()
        val sof = (0 until oversizedHeader.size - 8).first {
            oversizedHeader[it] == 0xff.toByte() && oversizedHeader[it + 1] == 0xc0.toByte()
        }
        // A tiny file declares a 65535-square raster, then has deliberately corrupt scan data.
        oversizedHeader[sof + 5] = 0xff.toByte()
        oversizedHeader[sof + 6] = 0xff.toByte()
        oversizedHeader[sof + 7] = 0xff.toByte()
        oversizedHeader[sof + 8] = 0xff.toByte()
        rejected(file(oversizedHeader))
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `rejects corrupt truncated multi-image JPEG and trailing data`() {
        val valid = jpeg()
        rejected(file(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())))
        rejected(file(valid.copyOf(valid.size / 2)))
        rejected(file(valid.copyOf(valid.size / 2) + byteArrayOf(0xff.toByte(), 0xd9.toByte())))
        rejected(file(valid + valid))
        rejected(file(valid + byteArrayOf(1, 2, 3)))
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `rejects a lying multipart size using bounded stream reads`() {
        val content = ByteArray(5 * 1024 * 1024 + 1)
        val lyingFile = object : MockMultipartFile("photo", "image.jpg", "image/jpeg", content) {
            override fun getSize() = 1L
        }
        rejected(lyingFile)
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `failed PUT compensates only its profile object`() {
        stub.failPuts = true
        val error = assertThrows(BusinessException::class.java) { storage.upload(file(jpeg())) }
        assertEquals(ErrorCode.PROFILE_IMAGE_FAILED, error.errorCode)
        assertEquals(1, stub.requests.count { it.method == "DELETE" })
    }

    private fun rejected(file: MockMultipartFile) {
        assertEquals(ErrorCode.INVALID_INPUT,
            assertThrows(BusinessException::class.java) { storage.upload(file) }.errorCode)
    }

    private fun file(bytes: ByteArray, contentType: String? = "image/jpeg") =
        MockMultipartFile("photo", "untrusted-filename.jpg", contentType, bytes)

    private fun jpeg(width: Int = 64, height: Int = 64): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", it)
    }.toByteArray()

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xff.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }
}
