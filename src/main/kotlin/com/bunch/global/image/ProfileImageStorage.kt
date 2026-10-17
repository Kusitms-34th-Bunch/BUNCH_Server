package com.bunch.global.image

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.multipart.MultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

@Service
class ProfileImageStorage(
    @Value("\${cloudinary.cloud-name:}")
    private val cloudName: String = "",
    @Value("\${cloudinary.api-key:}")
    private val apiKey: String = "",
    @Value("\${cloudinary.api-secret:}")
    private val apiSecret: String = "",
    @Value("\${cloudinary.upload-base-url:https://api.cloudinary.com}")
    private val uploadBaseUrl: String = "https://api.cloudinary.com"
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val client = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
            ).apply {
                setReadTimeout(Duration.ofSeconds(10))
            }
        )
        .build()

    fun upload(file: MultipartFile): StoredImage {
        val image = sanitize(file)
        val publicId = "profiles/${UUID.randomUUID()}"
        val endpoint = endpoint("upload")
        try {
            val body = LinkedMultiValueMap<String, Any>().apply {
                val resource = object : ByteArrayResource(image.bytes) {
                    override fun getFilename() = "profile.jpg"
                }
                add(
                    "file",
                    HttpEntity(
                        resource,
                        HttpHeaders().apply {
                            contentType = MediaType.IMAGE_JPEG
                        }
                    )
                )
                add("public_id", publicId)
                add("overwrite", "false")
            }
            val response = client.post().uri(endpoint)
                .headers { it.setBasicAuth(apiKey, apiSecret) }
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body).retrieve()
                .onStatus(
                    { !it.is2xxSuccessful }
                ) { _, _ -> throw IllegalStateException() }
                .body(JsonNode::class.java)
                ?: throw IllegalStateException()
            val url = response.path("secure_url").takeIf { it.isTextual }?.textValue()
                ?: throw IllegalStateException()
            val secureUrl = URI(url)
            check(
                secureUrl.scheme == "https" &&
                    !secureUrl.host.isNullOrBlank() &&
                    secureUrl.rawUserInfo == null &&
                    secureUrl.rawFragment == null
            )
            check(response.path("public_id").isTextual && response.path("public_id").textValue() == publicId)
            check(
                response.path("resource_type").textValue() == "image" &&
                    response.path("format").textValue() == "jpg"
            )
            check(
                response.path("width").isIntegralNumber &&
                    response.path("width").canConvertToInt() &&
                    response.path("width").intValue() == image.size &&
                    response.path("height").isIntegralNumber &&
                    response.path("height").canConvertToInt() &&
                    response.path("height").intValue() == image.size
            )
            return StoredImage(publicId, url)
        } catch (_: Exception) {
            // The request may have succeeded remotely even when its response was lost or invalid.
            delete(publicId)
            throw BusinessException(ErrorCode.PROFILE_IMAGE_FAILED)
        }
    }

    fun delete(publicId: String?) {
        if (publicId.isNullOrBlank()) return
        try {
            val body = LinkedMultiValueMap<String, String>().apply {
                add("public_id", publicId)
                add("invalidate", "true")
            }
            val response = client.post().uri(endpoint("destroy"))
                .headers { it.setBasicAuth(apiKey, apiSecret) }
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body).retrieve()
                .onStatus(
                    { !it.is2xxSuccessful }
                ) { _, _ -> throw IllegalStateException() }
                .body(JsonNode::class.java)
            check(response?.path("result")?.textValue() in setOf("ok", "not found"))
        } catch (_: Exception) {
            // Never include the exception: HTTP failures can contain URLs, headers or credentials.
            logger.warn("Profile image deletion failed: publicId={}", publicId)
        }
    }

    private fun endpoint(action: String): URI {
        try {
            check(
                cloudName.matches(Regex("[A-Za-z0-9_-]+")) &&
                    apiKey.isNotBlank() &&
                    apiSecret.isNotBlank()
            )
            val base = URI(uploadBaseUrl.trimEnd('/'))
            val localStub = base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost", "[::1]")
            check(
                (base.scheme == "https" || localStub) &&
                    !base.host.isNullOrBlank() &&
                    base.rawUserInfo == null &&
                    base.rawQuery == null &&
                    base.rawFragment == null &&
                    base.rawPath.isNullOrEmpty()
            )
            return URI("$base/v1_1/$cloudName/image/$action")
        } catch (_: Exception) {
            // Missing or invalid settings disable storage, never application startup.
            throw BusinessException(ErrorCode.PROFILE_IMAGE_FAILED)
        }
    }

    private fun sanitize(file: MultipartFile): SanitizedImage {
        try {
            check(file.contentType.equals(MediaType.IMAGE_JPEG_VALUE, ignoreCase = true))
            check(file.size in 1..MAX_BYTES.toLong())
            val bytes = file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            check(bytes.size in 4..MAX_BYTES)
            check(
                bytes[0] == 0xff.toByte() &&
                    bytes[1] == 0xd8.toByte() &&
                    bytes[bytes.lastIndex - 1] == 0xff.toByte() &&
                    bytes.last() == 0xd9.toByte()
            )
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                check(readers.hasNext())
                val reader = readers.next()
                try {
                    check(reader.formatName.equals("JPEG", ignoreCase = true))
                    reader.setInput(input, false, false)
                    var warning = false
                    reader.addIIOReadWarningListener { _, _ -> warning = true }
                    // Inspect SOF dimensions before getNumImages/read can process the image data.
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    check(width in 1..MAX_DIMENSION && height == width)
                    check(reader.getNumImages(true) == 1 && !warning)
                    val decoded = reader.read(0) ?: throw IllegalArgumentException()
                    check(!warning && decoded.width == width && decoded.height == height)
                    val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                    val graphics = rgb.createGraphics()
                    try {
                        graphics.drawImage(decoded, 0, 0, null)
                    } finally {
                        graphics.dispose()
                    }
                    val output = ByteArrayOutputStream()
                    // A new RGB image and default writer metadata discard EXIF, comments and profiles.
                    check(ImageIO.write(rgb, "jpeg", output))
                    return SanitizedImage(output.toByteArray(), width)
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: Exception) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
    }

    private data class SanitizedImage(val bytes: ByteArray, val size: Int)

    private companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val MAX_DIMENSION = 1024
    }
}
