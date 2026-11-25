package com.bunch.global.image

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

class R2ObjectStorageTest {
    @Test
    fun `official R2 endpoint signs locally without opening the bucket`() {
        val objects = R2ObjectStorage("https://67e6dbcbc1d362cf8d959c8b3131593f.r2.cloudflarestorage.com",
            "bunch-dev", "test-key", "test-secret")
        try {
            val uri = URI(objects.sign("photos/42/original.jpg"))
            assertEquals("/bunch-dev/photos/42/original.jpg", uri.path)
            assertEquals("67e6dbcbc1d362cf8d959c8b3131593f.r2.cloudflarestorage.com", uri.host)
            assertTrue(validSignature(uri, "GET"))
        } finally {
            objects.close()
        }
    }

    @Test
    fun `direct wire PUT uses a compatible S3 client configuration`() {
        R2TestServer().use {
            it.storage.put("profiles/probe/profile.jpg", byteArrayOf(1, 2, 3))
            assertEquals("private, max-age=3600", it.requests.single().cacheControl)
        }
    }

    @Test
    fun `signatures are local use GET and expire two hours after the signing hour`() {
        R2TestServer().use { stub ->
            val urls = ImageUrls(stub.storage)
            val full = URI(urls.full("photos/42/original.jpg"))
            val thumb = URI(urls.thumbnail("photos/42/original.jpg"))
            val profile = URI(urls.profile("profiles/7/profile.jpg")!!)
            assertEquals("/bunch-test/photos/42/original.jpg", full.path)
            assertEquals("/bunch-test/photos/42/thumbnail.jpg", thumb.path)
            assertEquals("/bunch-test/profiles/7/profile.jpg", profile.path)
            assertTrue(full.query.contains("X-Amz-Expires=7200"))
            assertTrue(full.query.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"))
            assertTrue(full.query.contains("X-Amz-SignedHeaders=host"))
            assertFalse(full.query.contains("x-amz-checksum"))
            assertEquals("private, max-age=3600", parameter(full, "response-cache-control"))
            assertNull(urls.profile(null))
            assertTrue(validSignature(full, "GET"))
            assertFalse(validSignature(full, "PUT"))
            assertFalse(validSignature(URI(full.toString().replace("/42/", "/43/")), "GET"))
            assertFalse(validSignature(URI(full.toString().replace("3600", "7200")), "GET"))
            assertTrue(stub.requests.isEmpty())
        }
    }

    @Test
    fun `separate instances return identical URLs within the same hour`() {
        listOf("photos/42/original.jpg", "photos/42/thumbnail.jpg", "profiles/7/profile.jpg").forEach { key ->
            val first = signedAt(key, "2026-11-25T11:00:00Z")
            val later = signedAt(key, "2026-11-25T11:59:59.999999999Z")
            assertEquals(first, later)
            assertEquals("20261125T110000Z", parameter(first, "X-Amz-Date"))
            assertTrue(validSignature(first, "GET"))
        }
    }

    @Test
    fun `hour and UTC date boundaries rotate URLs and credential dates`() {
        val key = "photos/42/thumbnail.jpg"
        val beforeHour = signedAt(key, "2026-11-25T22:59:59Z")
        val nextHour = signedAt(key, "2026-11-25T23:00:00Z")
        val beforeMidnight = signedAt(key, "2026-11-25T23:59:59Z")
        val midnight = signedAt(key, "2026-11-26T00:00:00Z")
        assertNotEquals(beforeHour, nextHour)
        assertEquals(nextHour, beforeMidnight)
        assertNotEquals(beforeMidnight, midnight)
        assertEquals("20261126T000000Z", parameter(midnight, "X-Amz-Date"))
        assertTrue(parameter(midnight, "X-Amz-Credential").contains("/20261126/auto/s3/aws4_request"))
        assertTrue(validSignature(midnight, "GET"))
    }

    @Test
    fun `responses retain at least one hour of signature validity`() {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX").withZone(ZoneOffset.UTC)
        listOf("2026-11-25T11:00:00Z", "2026-11-25T11:59:59.999999999Z").forEach { time ->
            val url = signedAt("photos/42/original.jpg", time)
            val signed = Instant.from(formatter.parse(parameter(url, "X-Amz-Date")))
            val expires = signed.plusSeconds(parameter(url, "X-Amz-Expires").toLong())
            assertEquals(Instant.parse("2026-11-25T13:00:00Z"), expires)
            val remaining = Duration.between(Instant.parse(time), expires)
            assertTrue(remaining > Duration.ofHours(1))
            assertTrue(remaining <= Duration.ofHours(2))
        }
    }

    @Test
    fun `buckets original thumbnails and profiles remain separate cache identities`() {
        val time = "2026-11-25T11:01:00Z"
        val original = signedAt("photos/42/original.jpg", time)
        val thumbnail = signedAt("photos/42/thumbnail.jpg", time)
        val profile = signedAt("profiles/42/profile.jpg", time)
        val otherBucket = signedAt("photos/42/original.jpg", time, "other-test")
        assertEquals(4, setOf(original, thumbnail, profile, otherBucket).size)
    }

    @Test
    fun `external URLs traversal archive and malformed keys are never signed`() {
        R2TestServer().use { stub ->
            listOf("https://elsewhere/image.jpg", "photos/../original.jpg", "archive/a.jpg", "profiles/a/thumbnail.jpg")
                .forEach { assertThrows(IllegalArgumentException::class.java) { stub.storage.sign(it) } }
            assertTrue(stub.requests.isEmpty())
        }
    }

    @Test
    fun `photo PUT preserves exact bytes and stores a second thumbnail`() {
        R2TestServer().use { stub ->
            val storage = PhotoImageStorage(stub.storage)
            val bytes = jpeg()
            val stored = storage.upload(storage.inspect(MockMultipartFile("image", "image.jpg", "image/jpeg", bytes)))
            assertTrue(stored.objectKey.matches(Regex("photos/[a-f0-9-]+/original.jpg")))
            val puts = stub.requests.filter { it.method == "PUT" }
            assertEquals(2, puts.size)
            assertArrayEquals(bytes, puts.first().body)
            val thumbnail = ImageIO.read(puts.last().body.inputStream())
            assertEquals(600, thumbnail.width)
            assertEquals(600, thumbnail.height)
        }
    }

    @Test
    fun `partial photo upload compensates both objects even when first delete fails`() {
        R2TestServer().use { stub ->
            stub.failThumbnail = true
            stub.failDeletes = true
            val storage = PhotoImageStorage(stub.storage)
            val bytes = jpeg()
            val error = assertThrows(BusinessException::class.java) {
                storage.upload(storage.inspect(MockMultipartFile("image", "image.jpg", "image/jpeg", bytes)))
            }
            assertEquals(ErrorCode.PHOTO_IMAGE_STORAGE_FAILED, error.errorCode)
            assertEquals(2, stub.requests.count { it.method == "DELETE" })
            assertTrue(stub.requests.any { it.method == "DELETE" && it.path.endsWith("thumbnail.jpg") })
        }
    }

    @Test
    fun `first PUT failure still compensates unknown remote outcome for the pair`() {
        R2TestServer().use { stub ->
            stub.failPuts = true
            val storage = PhotoImageStorage(stub.storage)
            val bytes = jpeg()
            assertThrows(BusinessException::class.java) {
                storage.upload(storage.inspect(MockMultipartFile("image", "image.jpg", "image/jpeg", bytes)))
            }
            assertEquals(1, stub.requests.count { it.method == "PUT" })
            assertEquals(2, stub.requests.count { it.method == "DELETE" })
        }
    }

    @Test
    fun `deletion failures log only object paths without upstream bodies or exceptions`() {
        val logger = org.slf4j.LoggerFactory.getLogger(R2ObjectStorage::class.java) as ch.qos.logback.classic.Logger
        val events = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            R2TestServer().use { stub ->
                stub.failDeletes = true
                stub.storage.deleteImage("photos/42/original.jpg")
                assertEquals(2, events.list.size)
                events.list.forEach {
                    assertNull(it.throwableProxy)
                    assertTrue(it.formattedMessage.startsWith("Image object deletion failed: objectKey=photos/42/"))
                    assertFalse(it.formattedMessage.contains("AccessDenied"))
                }
            }
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }

    @Test
    fun `shared asset deletion entry point removes photo pair and profile alone`() {
        R2TestServer().use { stub ->
            val storage = ProfileImageStorage(stub.storage)
            storage.delete("photos/42/original.jpg")
            storage.delete("profiles/7/profile.jpg")
            storage.delete(null)
            assertEquals(listOf("/bunch-test/photos/42/original.jpg", "/bunch-test/photos/42/thumbnail.jpg",
                "/bunch-test/profiles/7/profile.jpg"), stub.requests.map { it.path })
        }
    }

    @Test
    fun `unsafe or bucket-qualified endpoints fail before any network request`() {
        listOf("http://example.com", "https://user:secret@00000000000000000000000000000000.r2.cloudflarestorage.com",
            "https://00000000000000000000000000000000.r2.cloudflarestorage.com/bunch-test",
            "https://00000000000000000000000000000000.r2.cloudflarestorage.com?x=y").forEach { endpoint ->
            val storage = R2ObjectStorage(endpoint, "bunch-test", "test-key", "test-secret")
            assertThrows(Exception::class.java) { storage.sign("photos/42/original.jpg") }
            storage.close()
        }
    }

    @Test
    fun `missing configuration permits construction and blocks remote operations`() {
        val storage = R2ObjectStorage()
        assertThrows(Exception::class.java) { storage.sign("photos/42/original.jpg") }
        assertDoesNotThrow { storage.deleteImage("photos/42/original.jpg") }
        storage.close()
    }

    private fun signedAt(key: String, time: String, bucket: String = "bunch-test"): URI {
        val storage = R2ObjectStorage(
            "https://00000000000000000000000000000000.r2.cloudflarestorage.com",
            bucket,
            "test-key",
            "test-secret",
            Clock.fixed(Instant.parse(time), ZoneOffset.UTC)
        )
        return try {
            URI(storage.sign(key))
        } finally {
            storage.close()
        }
    }

    private fun parameter(uri: URI, name: String): String = java.net.URLDecoder.decode(
        uri.rawQuery.split('&').first { it.substringBefore('=') == name }.substringAfter('='),
        Charsets.UTF_8
    )

    private fun validSignature(uri: URI, method: String): Boolean {
        val parameters = uri.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val credential = java.net.URLDecoder.decode(parameters.getValue("X-Amz-Credential"), Charsets.UTF_8)
        val scope = credential.substringAfter('/')
        val timestamp = parameters.getValue("X-Amz-Date")
        val query = parameters.filterKeys { it != "X-Amz-Signature" }.toSortedMap()
            .entries.joinToString("&") { "${it.key}=${it.value}" }
        val canonical = listOf(method, uri.rawPath, query, "host:${uri.rawAuthority}\n", "host", "UNSIGNED-PAYLOAD")
            .joinToString("\n")
        val canonicalHash = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).hex()
        val toSign = "AWS4-HMAC-SHA256\n$timestamp\n$scope\n$canonicalHash"
        val parts = scope.split('/')
        var key = "AWS4test-secret".toByteArray()
        parts.forEach { key = hmac(key, it) }
        return hmac(key, toSign).hex() == parameters.getValue("X-Amz-Signature")
    }

    private fun hmac(key: ByteArray, value: String): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        doFinal(value.toByteArray())
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun jpeg() = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "jpeg", it)
    }.toByteArray()

}
