package com.bunch.global.image

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.mock.web.MockMultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

class ProfileImageStorageTest {
    private lateinit var server: HttpServer
    private lateinit var storage: ProfileImageStorage
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val mapper = jacksonObjectMapper()
    private var uploadStatus = 200
    private var uploadResponse: (Request) -> String = ::successfulUpload
    private var uploadLocation: String? = null
    private var deleteStatus = 200
    private var deleteResponse = """{"result":"ok"}"""

    @BeforeEach
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1_1/test-cloud/image/") { exchange ->
            exchange.use {
                // Record only a boolean so a failing assertion cannot print an Authorization value.
                val expected = "Basic " + Base64.getEncoder().encodeToString("test-key:test-secret".toByteArray())
                val request = Request(
                    exchange.requestMethod, exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Content-Type") ?: "",
                    exchange.requestBody.use { it.readBytes() },
                    exchange.requestHeaders.getFirst("Authorization") == expected
                )
                requests.add(request)
                val upload = request.path.endsWith("/upload")
                val response = (if (upload) uploadResponse(request) else deleteResponse).toByteArray(UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                if (upload) uploadLocation?.let { exchange.responseHeaders.add("Location", it) }
                exchange.sendResponseHeaders(if (upload) uploadStatus else deleteStatus, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
        }
        server.start()
        storage = ProfileImageStorage("test-cloud", "test-key", "test-secret", baseUrl())
    }

    @AfterEach
    fun teardown() { server.stop(0) }

    @Test
    fun `upload sends sanitized JPEG multipart with basic auth and unique non-overwriting image IDs`() {
        val source = jpeg()
        val first = storage.upload(file(source))
        val second = storage.upload(file(source))

        assertEquals(2, requests.size)
        assertNotEquals(first.publicId, second.publicId)
        listOf(first, second).zip(requests).forEach { (image, request) ->
            assertEquals("POST", request.method)
            assertEquals("/v1_1/test-cloud/image/upload", request.path)
            assertTrue(request.authenticated)
            assertTrue(request.contentType.startsWith("multipart/form-data;"))
            assertEquals(image.publicId, request.part("public_id"))
            assertNotNull(UUID.fromString(image.publicId.removePrefix("profiles/")))
            assertEquals("false", request.part("overwrite"))
            assertEquals("https://res.cloudinary.com/test/image/upload/${image.publicId}.jpg", image.url)
            val payload = request.part("file").toByteArray(ISO_8859_1)
            val decoded = ImageIO.read(ByteArrayInputStream(payload))
            assertEquals(64, decoded.width)
            assertEquals(64, decoded.height)
            val multipart = String(request.body, ISO_8859_1)
            assertTrue(multipart.contains("filename=\"profile.jpg\""))
            assertTrue(multipart.contains("Content-Type: image/jpeg"))
            assertFalse(multipart.contains("untrusted-filename"))
        }
    }

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

        val sanitized = requests.single().part("file").toByteArray(ISO_8859_1)
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
        assertEquals(2, requests.size)
    }

    @Test
    fun `rejects PNG disguised as JPEG and non JPEG content types`() {
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        rejected(file(png))
        rejected(file(jpeg(), "image/png"))
        rejected(file(jpeg(), null))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `rejects empty non-square oversized dimensions and files over five MiB`() {
        rejected(file(byteArrayOf()))
        rejected(file(jpeg(64, 32)))
        rejected(file(jpeg(1025, 1025)))
        rejected(file(ByteArray(5 * 1024 * 1024 + 1)))
        assertTrue(requests.isEmpty())
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
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `rejects corrupt truncated multi-image JPEG and trailing data`() {
        val valid = jpeg()
        rejected(file(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())))
        rejected(file(valid.copyOf(valid.size / 2)))
        rejected(file(valid.copyOf(valid.size / 2) + byteArrayOf(0xff.toByte(), 0xd9.toByte())))
        rejected(file(valid + valid))
        rejected(file(valid + byteArrayOf(1, 2, 3)))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `rejects a lying multipart size using bounded stream reads`() {
        val content = ByteArray(5 * 1024 * 1024 + 1)
        val lyingFile = object : MockMultipartFile("photo", "image.jpg", "image/jpeg", content) {
            override fun getSize() = 1L
        }
        rejected(lyingFile)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `missing credentials allow Spring context startup and fail upload without remote calls`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("empty-cloudinary", mapOf(
                "cloudinary.cloud-name" to "", "cloudinary.api-key" to "", "cloudinary.api-secret" to ""
            )))
            context.register(ProfileImageStorage::class.java)
            context.refresh()
            val unconfigured = context.getBean(ProfileImageStorage::class.java)
            failedUpload(unconfigured)
            assertDoesNotThrow { unconfigured.delete(null) }
        }
        listOf(
            ProfileImageStorage("", "test-key", "test-secret", baseUrl()),
            ProfileImageStorage("test-cloud", "", "test-secret", baseUrl()),
            ProfileImageStorage("test-cloud", "test-key", "", baseUrl())
        ).forEach { failedUpload(it) }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `Spring injects the four Cloudinary configuration properties`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("stub-cloudinary", mapOf(
                "cloudinary.cloud-name" to "test-cloud", "cloudinary.api-key" to "test-key",
                "cloudinary.api-secret" to "test-secret", "cloudinary.upload-base-url" to baseUrl()
            )))
            context.register(ProfileImageStorage::class.java)
            context.refresh()
            val result = context.getBean(ProfileImageStorage::class.java).upload(file(jpeg()))
            assertEquals(requests.single().part("public_id"), result.publicId)
            assertTrue(requests.single().authenticated)
        }
    }

    @Test
    fun `rejects nonlocal HTTP or credential-bearing upload base URLs`() {
        listOf("http://api.cloudinary.com", "https://user:secret@example.com", "${baseUrl()}?query=1",
            "https://example.com/#fragment").forEach { url ->
            failedUpload(ProfileImageStorage("test-cloud", "test-key", "test-secret", url))
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `non2xx upload cleans up the generated ID even when destroy fails`() {
        uploadStatus = 503
        uploadResponse = { """{"error":{"message":"unavailable"}}""" }
        deleteStatus = 500

        failedUpload()

        assertCleanup()
    }

    @Test
    fun `malformed and incomplete upload responses are failures with cleanup`() {
        listOf("not-json", "{}", "[]", "null").forEach { response ->
            requests.clear()
            uploadResponse = { response }
            failedUpload()
            assertCleanup()
        }
    }

    @Test
    fun `verifies public ID HTTPS URL image format resource type and exact dimensions`() {
        val mutations: List<Pair<String, Any>> = listOf(
            "public_id" to "unexpected-id", "public_id" to 123,
            "secure_url" to "http://res.cloudinary.com/test.jpg",
            "secure_url" to "https://user:secret@example.com/image.jpg",
            "secure_url" to "https:///missing-host.jpg", "secure_url" to 123,
            "resource_type" to "video", "format" to "png",
            "width" to 63, "height" to 63, "width" to "64", "height" to 64.0,
            "width" to 4294967360L
        )
        mutations.forEach { (field, value) ->
            requests.clear()
            uploadResponse = { request ->
                val body = mapper.readTree(successfulUpload(request)) as com.fasterxml.jackson.databind.node.ObjectNode
                body.set<com.fasterxml.jackson.databind.JsonNode>(field, mapper.valueToTree(value))
                body.toString()
            }
            failedUpload()
            assertCleanup()
        }
    }

    @Test
    fun `redirect upload response fails without forwarding credentials`() {
        val redirects = AtomicInteger()
        server.createContext("/redirect-target") { exchange ->
            redirects.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        uploadStatus = 302
        uploadLocation = "${baseUrl()}/redirect-target"
        failedUpload()
        assertCleanup()
        assertEquals(0, redirects.get())
    }

    @Test
    fun `destroy uses image endpoint basic auth public ID and cache invalidation`() {
        storage.delete("profiles/old image")
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1_1/test-cloud/image/destroy", request.path)
        assertTrue(request.authenticated)
        assertTrue(request.contentType.startsWith("application/x-www-form-urlencoded"))
        assertEquals(mapOf("public_id" to "profiles/old image", "invalidate" to "true"), request.form())
    }

    @Test
    fun `delete null and blank are noops and remote failures never escape`() {
        storage.delete(null)
        storage.delete("")
        storage.delete(" ")
        assertTrue(requests.isEmpty())
        listOf(500 to "{}", 200 to "not-json", 200 to "{}", 200 to """{"result":"error"}""",
            200 to """{"result":"not found"}""").forEach { (status, body) ->
            deleteStatus = status
            deleteResponse = body
            assertDoesNotThrow { storage.delete("profiles/old") }
        }
        assertDoesNotThrow { ProfileImageStorage().delete("profiles/unconfigured") }
        assertEquals(5, requests.size)
    }

    @Test
    fun `delete failure logs only the public ID without response data or exception details`() {
        val logger = LoggerFactory.getLogger(ProfileImageStorage::class.java) as Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.WARN
        logger.addAppender(appender)
        try {
            deleteStatus = 500
            deleteResponse = """{"error":{"message":"sensitive-response-marker"}}"""
            storage.delete("profiles/cleanup-needed")
            val event = appender.list.single()
            assertEquals("Profile image deletion failed: publicId=profiles/cleanup-needed", event.formattedMessage)
            assertNull(event.throwableProxy)
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }

    private fun assertCleanup() {
        assertEquals(2, requests.size)
        val upload = requests[0]
        val destroy = requests[1]
        assertTrue(destroy.authenticated)
        assertEquals("/v1_1/test-cloud/image/destroy", destroy.path)
        assertEquals(upload.part("public_id"), destroy.form()["public_id"])
        assertEquals("true", destroy.form()["invalidate"])
    }

    private fun failedUpload(target: ProfileImageStorage = storage) {
        assertEquals(ErrorCode.PROFILE_IMAGE_FAILED,
            assertThrows(BusinessException::class.java) { target.upload(file(jpeg())) }.errorCode)
    }

    private fun rejected(file: MockMultipartFile) {
        assertEquals(ErrorCode.INVALID_INPUT,
            assertThrows(BusinessException::class.java) { storage.upload(file) }.errorCode)
    }

    private fun successfulUpload(request: Request): String {
        val id = request.part("public_id")
        val image = ImageIO.read(ByteArrayInputStream(request.part("file").toByteArray(ISO_8859_1)))
        return """{"public_id":"$id","secure_url":"https://res.cloudinary.com/test/image/upload/$id.jpg",
            "resource_type":"image","format":"jpg","width":${image.width},"height":${image.height}}"""
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun file(bytes: ByteArray, contentType: String? = "image/jpeg") =
        MockMultipartFile("photo", "untrusted-filename.jpg", contentType, bytes)

    private fun jpeg(width: Int = 64, height: Int = 64): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", it)
    }.toByteArray()

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xff.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private class Request(
        val method: String, val path: String, val contentType: String, val body: ByteArray,
        val authenticated: Boolean
    ) {
        fun part(name: String): String {
            val boundary = contentType.substringAfter("boundary=").trim('"')
            val section = String(body, ISO_8859_1).split("--$boundary")
                .single { it.contains("name=\"$name\"") }
            return section.substringAfter("\r\n\r\n").removeSuffix("\r\n")
        }

        fun form(): Map<String, String> = String(body, UTF_8).split('&').associate { parameter ->
            URLDecoder.decode(parameter.substringBefore('='), UTF_8) to
                URLDecoder.decode(parameter.substringAfter('='), UTF_8)
        }
    }
}
