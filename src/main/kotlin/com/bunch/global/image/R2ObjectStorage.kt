package com.bunch.global.image

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner.AuthLocation
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** R2 only. Signing uses the local credentials and never performs a HEAD or GET. */
@Service
class R2ObjectStorage(
    @Value("\${r2.endpoint:}")
    private val endpoint: String = "",
    @Value("\${r2.bucket:}")
    private val bucket: String = "",
    @Value("\${r2.access-key-id:}")
    private val accessKeyId: String = "",
    @Value("\${r2.secret-access-key:}")
    private val secretAccessKey: String = "",
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val signer = AwsV4HttpSigner.create()
    private val clientDelegate = lazy {
        S3Client.builder()
            .endpointOverride(validEndpoint())
            .credentialsProvider(credentials())
            .region(Region.of("auto"))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build()
            )
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .overrideConfiguration {
                it.apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(30))
            }
            .build()
    }
    fun put(key: String, bytes: ByteArray) {
        ImageObjectKeys.requireImage(key)
        clientDelegate.value.putObject(
            { it.bucket(bucket).key(key).contentType("image/jpeg").cacheControl(IMAGE_CACHE_CONTROL) },
            RequestBody.fromBytes(bytes)
        )
    }

    fun sign(key: String): String {
        ImageObjectKeys.requireImage(key)
        val request = SdkHttpRequest.builder()
            .uri(validEndpoint().resolve("/$bucket/$key"))
            .method(SdkHttpMethod.GET)
            .appendRawQueryParameter("response-cache-control", IMAGE_CACHE_CONTROL)
            .build()
        val signingTime = clock.instant().truncatedTo(ChronoUnit.HOURS)
        return signer.sign {
            it.identity(credentials().resolveCredentials())
                .request(request)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
                .putProperty(AwsV4HttpSigner.REGION_NAME, "auto")
                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, Duration.ofHours(2))
                .putProperty(AwsV4HttpSigner.SIGNING_CLOCK, Clock.fixed(signingTime, ZoneOffset.UTC))
        }.request().getUri().toString()
    }

    fun deleteImage(key: String?) {
        if (key.isNullOrBlank()) return
        val keys = try {
            ImageObjectKeys.requireImage(key)
            if (key.startsWith("photos/")) listOf(key, ImageObjectKeys.thumbnail(key)) else listOf(key)
        } catch (_: Exception) {
            logger.warn("Image deletion rejected an invalid object key")
            return
        }
        keys.forEach { objectKey ->
            try {
                clientDelegate.value.deleteObject { it.bucket(bucket).key(objectKey) }
            } catch (_: Exception) {
                // Never log HTTP exceptions, signed URLs, credentials or provider response bodies.
                logger.warn("Image object deletion failed: objectKey={}", objectKey)
            }
        }
    }

    private fun validEndpoint(): URI {
        val uri = URI(endpoint)
        val local = uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")
        val r2Host = uri.host?.matches(Regex("[a-f0-9]{32}\\.r2\\.cloudflarestorage\\.com")) == true
        require(local || (uri.scheme == "https" && r2Host))
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        require(bucket.matches(Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")))
        return uri
    }

    private fun credentials(): StaticCredentialsProvider {
        require(accessKeyId.isNotBlank() && secretAccessKey.isNotBlank())
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey))
    }

    @PreDestroy
    fun close() {
        if (clientDelegate.isInitialized()) clientDelegate.value.close()
    }

    private companion object {
        const val IMAGE_CACHE_CONTROL = "private, max-age=3600"
    }
}
