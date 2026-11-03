package com.bunch.global.image

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

class PhotoImageStorageTest {
    private val uploadUrl = "https://api.cloudinary.com/v1_1/test-cloud/image/upload"
    private val destroyUrl = "https://api.cloudinary.com/v1_1/test-cloud/image/destroy"
    private val basicAuth = "Basic " + Base64.getEncoder().encodeToString("test-key:test-secret".toByteArray())
    private val publicIdPart = Regex("name=\"public_id\"\\r\\n(?:[A-Za-z-]+:[^\\r]*\\r\\n)*\\r\\n([^\\r]+)")
    private lateinit var server: MockRestServiceServer
    private lateinit var storage: PhotoImageStorage
    private var uploadBody: ByteArray = byteArrayOf()

    @BeforeEach
    fun setup() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        storage = PhotoImageStorage("test-cloud", "test-key", "test-secret", client = builder.build())
    }

    @Test
    fun `accepts a JPEG and keeps the uploaded bytes untouched`() {
        val source = jpeg(1200, 800)

        val image = storage.inspect(file(source))

        assertArrayEquals(source, image.bytes)
        assertEquals(1200, image.width)
        assertEquals(800, image.height)
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
        val small = PhotoImageStorage("test-cloud", "test-key", "test-secret", maxDimension = 2560)

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

    @Test
    fun `upload sends the bytes as an unmodified JPEG with basic auth and a unique photos path`() {
        val image = storage.inspect(file(jpeg(1200, 800)))
        repeat(2) { respondUpload(1200, 800) }
        val stored = listOf(storage.upload(image), storage.upload(image))

        server.verify()
        assertNotEquals(stored[0].publicId, stored[1].publicId)
        stored.forEach { assertNotNull(UUID.fromString(it.publicId.removePrefix("photos/"))) }
        assertTrue(stored.all { it.url.startsWith("https://res.cloudinary.com/test/image/upload/") })
        val multipart = String(uploadBody, ISO_8859_1)
        assertTrue(multipart.contains("filename=\"photo.jpg\""))
        assertTrue(multipart.contains("Content-Type: image/jpeg"))
        assertTrue(Regex("name=\"overwrite\"\\r\\n(?:[A-Za-z-]+:[^\\r]*\\r\\n)*\\r\\nfalse").containsMatchIn(multipart))
        assertTrue(multipart.contains(String(image.bytes, ISO_8859_1)))
    }

    @Test
    fun `upload failures clean up the generated id and report a storage error`() {
        val image = storage.inspect(file(jpeg(64, 64)))
        val failures = listOf<() -> Unit>(
            { server.expect(requestTo(uploadUrl)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)) },
            { server.expect(requestTo(uploadUrl)).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON)) },
            { server.expect(requestTo(uploadUrl)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON)) },
            { respondUpload { it.replace("\"width\":64", "\"width\":65") } },
            { respondUpload { it.replace("\"format\":\"jpg\"", "\"format\":\"png\"") } },
            { respondUpload { it.replace("https://", "http://") } },
            { respondUpload { it.replace("\"resource_type\":\"image\"", "\"resource_type\":\"video\"") } }
        )

        failures.forEach { arrange ->
            server.reset()
            arrange()
            server.expect(requestTo(destroyUrl))
                .andRespond(withSuccess("{\"result\":\"ok\"}", MediaType.APPLICATION_JSON))

            val error = assertThrows(BusinessException::class.java) { storage.upload(image) }

            assertEquals(ErrorCode.PHOTO_IMAGE_STORAGE_FAILED, error.errorCode)
            server.verify()
        }
    }

    @Test
    fun `delete posts the public id to the destroy endpoint and tolerates a missing file`() {
        server.expect(requestTo(destroyUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect {
                assertEquals(basicAuth, it.headers.getFirst("Authorization"))
                assertTrue(String((it as MockClientHttpRequest).bodyAsBytes).contains("public_id=photos%2Fold"))
            }
            .andRespond(withSuccess("{\"result\":\"not found\"}", MediaType.APPLICATION_JSON))

        storage.delete("photos/old")

        server.verify()
    }

    @Test
    fun `delete never throws and does nothing for a blank id`() {
        server.expect(requestTo(destroyUrl)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertDoesNotThrow { storage.delete("photos/old") }
        assertDoesNotThrow { storage.delete(null) }
        assertDoesNotThrow { storage.delete(" ") }
        server.verify()
    }

    @Test
    fun `missing credentials or unsafe base URLs fail without any remote call`() {
        val image = storage.inspect(file(jpeg()))
        val unsafe = listOf(
            offlineStorage(cloud = ""),
            offlineStorage(key = ""),
            offlineStorage(secret = ""),
            offlineStorage(base = "http://api.cloudinary.com"),
            offlineStorage(base = "https://u:p@example.com")
        )

        unsafe.forEach {
            val error = assertThrows(BusinessException::class.java) { it.upload(image) }
            assertEquals(ErrorCode.PHOTO_IMAGE_STORAGE_FAILED, error.errorCode)
        }
    }

    @Test
    fun `Spring wires the configuration properties and keeps startup working without credentials`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(
                MapPropertySource(
                    "photo-image",
                    mapOf(
                        "cloudinary.cloud-name" to "",
                        "cloudinary.api-key" to "",
                        "cloudinary.api-secret" to "",
                        "photo-image.max-dimension" to "2560"
                    )
                )
            )
            context.register(PhotoImageStorage::class.java)
            context.refresh()
            val bean = context.getBean(PhotoImageStorage::class.java)

            assertDoesNotThrow { bean.inspect(file(jpeg(2560, 1))) }
            assertThrows(PhotoImageRejectedException::class.java) { bean.inspect(file(jpeg(2561, 1))) }
            assertThrows(BusinessException::class.java) { bean.upload(bean.inspect(file(jpeg()))) }
            assertDoesNotThrow { bean.delete(null) }
        }
    }

    private fun respondUpload(width: Int = 64, height: Int = 64, mutate: (String) -> String = { it }) {
        server.expect(requestTo(uploadUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect { request ->
                assertEquals(basicAuth, request.headers.getFirst("Authorization"))
                assertTrue(request.headers.contentType.toString().startsWith("multipart/form-data"))
            }
            .andRespond { request ->
                uploadBody = (request as MockClientHttpRequest).bodyAsBytes
                val publicId = publicIdPart.find(String(uploadBody, ISO_8859_1))!!.groupValues[1]
                assertTrue(publicId.startsWith("photos/"))
                val body = """{"secure_url":"https://res.cloudinary.com/test/image/upload/v1/$publicId.jpg",""" +
                    """"public_id":"$publicId","resource_type":"image","format":"jpg",""" +
                    """"width":$width,"height":$height}"""
                withSuccess(mutate(body), MediaType.APPLICATION_JSON).createResponse(request)
            }
    }

    // A mock server without expectations fails the test if the storage tries to call out.
    private fun offlineStorage(
        cloud: String = "test-cloud",
        key: String = "test-key",
        secret: String = "test-secret",
        base: String = "https://api.cloudinary.com"
    ): PhotoImageStorage {
        val builder = RestClient.builder()
        MockRestServiceServer.bindTo(builder).build()
        return PhotoImageStorage(cloud, key, secret, base, client = builder.build())
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
