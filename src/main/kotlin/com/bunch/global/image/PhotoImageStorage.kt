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
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

@Service
class PhotoImageStorage(
    @Value("\${cloudinary.cloud-name:}")
    private val cloudName: String = "",
    @Value("\${cloudinary.api-key:}")
    private val apiKey: String = "",
    @Value("\${cloudinary.api-secret:}")
    private val apiSecret: String = "",
    @Value("\${cloudinary.upload-base-url:https://api.cloudinary.com}")
    private val uploadBaseUrl: String = "https://api.cloudinary.com",
    @Value("\${photo-image.max-dimension:$DEFAULT_MAX_DIMENSION}")
    private val maxDimension: Int = DEFAULT_MAX_DIMENSION,
    @Value("\${photo-image.read-timeout-seconds:$DEFAULT_READ_TIMEOUT_SECONDS}")
    readTimeoutSeconds: Long = DEFAULT_READ_TIMEOUT_SECONDS,
    private val client: RestClient = restClient(readTimeoutSeconds)
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Checks a client upload without decoding it. The bytes are returned untouched so the caller can hash
     * them once and decide whether to [upload] at all.
     */
    fun inspect(file: MultipartFile): PhotoImage {
        if (!file.contentType.equals(MediaType.IMAGE_JPEG_VALUE, ignoreCase = true)) {
            reject(PhotoImageRejection.UNSUPPORTED_FORMAT)
        }
        if (file.size > MAX_BYTES) reject(PhotoImageRejection.TOO_LARGE)
        val bytes = try {
            file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
        } catch (_: IOException) {
            reject(PhotoImageRejection.UNSUPPORTED_FORMAT)
        }
        if (bytes.size > MAX_BYTES) reject(PhotoImageRejection.TOO_LARGE)
        if (bytes.size < 4 || bytes[bytes.lastIndex - 1] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) {
            reject(PhotoImageRejection.UNSUPPORTED_FORMAT)
        }
        val header = try {
            JpegHeaderInspector.inspect(bytes)
        } catch (_: RuntimeException) {
            reject(PhotoImageRejection.UNSUPPORTED_FORMAT)
        }
        if (maxOf(header.width, header.height) > maxDimension) reject(PhotoImageRejection.RESOLUTION_EXCEEDED)
        if (header.hasLocationMetadata) reject(PhotoImageRejection.LOCATION_METADATA)
        return PhotoImage(bytes, header.width, header.height)
    }

    fun upload(image: PhotoImage): StoredImage {
        val publicId = "photos/${UUID.randomUUID()}"
        val endpoint = endpoint("upload")
        try {
            val body = LinkedMultiValueMap<String, Any>().apply {
                val resource = object : ByteArrayResource(image.bytes) {
                    override fun getFilename() = "photo.jpg"
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
                    response.path("width").intValue() == image.width &&
                    response.path("height").isIntegralNumber &&
                    response.path("height").canConvertToInt() &&
                    response.path("height").intValue() == image.height
            )
            return StoredImage(publicId, url)
        } catch (_: Exception) {
            // The request may have succeeded remotely even when its response was lost or invalid.
            delete(publicId)
            throw BusinessException(ErrorCode.PHOTO_IMAGE_STORAGE_FAILED)
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
            logger.warn("Photo image deletion failed: publicId={}", publicId)
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
            throw BusinessException(ErrorCode.PHOTO_IMAGE_STORAGE_FAILED)
        }
    }

    private fun reject(reason: PhotoImageRejection): Nothing = throw PhotoImageRejectedException(reason)

    private companion object {
        const val MAX_BYTES = 30 * 1024 * 1024
    }
}

private const val DEFAULT_MAX_DIMENSION = 4096
private const val DEFAULT_READ_TIMEOUT_SECONDS = 60L

private fun restClient(readTimeoutSeconds: Long): RestClient = RestClient.builder()
    .requestFactory(
        JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        ).apply {
            // A 30 MB upload needs far longer than the 10 seconds a small profile image gets.
            setReadTimeout(Duration.ofSeconds(readTimeoutSeconds))
        }
    )
    .build()
