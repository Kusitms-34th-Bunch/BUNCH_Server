package com.bunch.notification.service

import com.bunch.notification.domain.PushDeviceEnvironment
import com.bunch.notification.dto.ApnsResponse
import com.bunch.notification.dto.PushPayload
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import org.springframework.stereotype.Service
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import java.util.UUID

@Service
class ApnsClient(
    private val settings: PushApnsSettings,
    private val mapper: ObjectMapper,
    private val clock: Clock
) {
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private var providerToken: String? = null
    private var issuedAt: Instant? = null
    private var signingKey: PrivateKey? = null

    fun configured(): Boolean = settings.teamId.isNotBlank() &&
        settings.keyId.isNotBlank() && settings.privateKey.isNotBlank()

    fun send(
        environment: PushDeviceEnvironment,
        topic: String,
        deviceToken: String,
        jobId: UUID,
        payload: PushPayload,
        expiresAt: Instant
    ): ApnsResponse {
        require(deviceToken.matches(Regex("[0-9a-f]+")))
        val bytes = mapper.writeValueAsBytes(payload)
        require(bytes.size <= 4096)
        val now = clock.instant()
        val request = HttpRequest.newBuilder(settings.endpoint(environment).resolve("/3/device/$deviceToken"))
            .timeout(Duration.ofSeconds(10))
            .header("authorization", "bearer ${token(now)}")
            .header("apns-topic", topic)
            .header("apns-push-type", "alert")
            .header("apns-priority", "10")
            .header("apns-id", jobId.toString())
            .header("apns-expiration", expiresAt.epochSecond.toString())
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val reason = if (response.statusCode() == 200) null else {
                runCatching { mapper.readTree(response.body()).path("reason").textValue() }
                    .getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,80}")) }
                    ?: "UnexpectedResponse"
            }
            if (reason == "ExpiredProviderToken") expiredProviderToken(now)
            ApnsResponse(
                response.statusCode(),
                reason,
                response.headers().firstValue("retry-after").orElse(null)?.let { retryAfter(it, now) }
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ApnsResponse(503, "ConnectionFailure")
        } catch (_: java.io.IOException) {
            ApnsResponse(503, "ConnectionFailure")
        }
    }

    @Synchronized
    private fun token(now: Instant): String {
        val previous = issuedAt
        if (providerToken != null && previous != null && now < previous.plus(Duration.ofMinutes(50))) {
            return requireNotNull(providerToken)
        }
        settings.requireProviderCredentials()
        val key = signingKey ?: KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(
                settings.privateKey.replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .filterNot(Char::isWhitespace)
            ))
        ).also { signingKey = it }
        return Jwts.builder()
            .setHeaderParam("kid", settings.keyId)
            .setIssuer(settings.teamId)
            .setIssuedAt(Date.from(now))
            .signWith(key, SignatureAlgorithm.ES256)
            .compact()
            .also {
                providerToken = it
                issuedAt = now
            }
    }

    @Synchronized
    private fun expiredProviderToken(now: Instant) {
        // APNs rejects unnecessary provider-token rotation within twenty minutes.
        if (issuedAt?.plus(Duration.ofMinutes(20))?.let { now >= it } == true) providerToken = null
    }

    private fun retryAfter(value: String, now: Instant): Instant? = value.toLongOrNull()
        ?.takeIf { it >= 0 && it <= Duration.ofDays(7).seconds }
        ?.let(now::plusSeconds)
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            .getOrNull()
}
