package com.bunch.auth.oidc

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.nimbusds.jwt.SignedJWT
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.security.MessageDigest
import java.util.Base64
import java.time.Duration
import java.time.Instant

internal class OidcVerifier(
    private val issuers: Set<String>,
    private val audience: String,
    jwksUri: String,
    private val nonceMode: NonceMode
) {
    enum class NonceMode { NONE, RAW, SHA256 }
    private val decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
        .jwsAlgorithm(SignatureAlgorithm.RS256).build().also {
            // All timestamps are checked below using the same accepted expiry used by replay storage.
            it.setJwtValidator { org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success() }
        }
    private val skew = Duration.ofSeconds(60)

    fun verify(idToken: String, nonce: String?): SocialIdentity {
        validateCanonicalEncoding(idToken)
        val parsed = try { SignedJWT.parse(idToken) } catch (_: Exception) {
            throw BusinessException(ErrorCode.MALFORMED_JWT)
        }
        if (parsed.header.algorithm.name != "RS256") invalid()
        val raw = try { parsed.payload.toJSONObject() } catch (_: Exception) { invalid() }
        if (raw == null) invalid()
        val rawIssuer = raw["iss"] as? String ?: invalid()
        val rawSubject = (raw["sub"] as? String)?.takeIf { it.isNotBlank() } ?: invalid()
        val rawAudience = when (val value = raw["aud"]) {
            is String -> listOf(value)
            is List<*> -> value.map { it as? String ?: invalid() }
            else -> invalid()
        }
        if (rawAudience.isEmpty() || rawAudience.any { it.isBlank() }) invalid()
        val rawIssuedAt = numericDate(raw["iat"])
        val rawExpiresAt = numericDate(raw["exp"])
        val rawNotBefore = if (raw.containsKey("nbf")) numericDate(raw["nbf"]) else null
        try { decoder.decode(idToken) } catch (e: JwtException) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is java.io.IOException || it is org.springframework.web.client.RestClientException ||
                    it is com.nimbusds.jose.RemoteKeySourceException }) {
                throw BusinessException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE)
            }
            invalid()
        } catch (_: IllegalArgumentException) { invalid() }
        val now = Instant.now()
        val exp = rawExpiresAt
        val iat = rawIssuedAt
        val acceptedUntil = exp.plus(skew)
        if (rawIssuer !in issuers || audience.isBlank() || audience !in rawAudience ||
            !acceptedUntil.isAfter(now) || iat.isAfter(now.plus(skew)) || !exp.isAfter(iat) ||
            rawNotBefore?.isAfter(now.plus(skew)) == true) invalid()
        val subject = rawSubject
        if (nonceMode != NonceMode.NONE) {
            if (nonce.isNullOrBlank()) throw BusinessException(ErrorCode.NONCE_MISMATCH)
            val expected = if (nonceMode == NonceMode.SHA256) {
                MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            } else nonce
            val actual = raw["nonce"] as? String
            if (actual == null || !MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
                throw BusinessException(ErrorCode.NONCE_MISMATCH)
            }
        }
        val verified = raw["email_verified"] == true || raw["email_verified"] == "true"
        val email = if (verified) (raw["email"] as? String)?.takeIf { it.isNotBlank() } else null
        return SocialIdentity(subject, email, acceptedUntil)
    }

    private fun validateCanonicalEncoding(token: String) {
        val segments = token.split('.')
        if (segments.size != 3) throw BusinessException(ErrorCode.MALFORMED_JWT)
        for (segment in segments) {
            if (!segment.matches(Regex("[A-Za-z0-9_-]+"))) throw BusinessException(ErrorCode.MALFORMED_JWT)
            val canonical = try {
                Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(segment))
            } catch (_: IllegalArgumentException) { throw BusinessException(ErrorCode.MALFORMED_JWT) }
            if (canonical != segment) throw BusinessException(ErrorCode.MALFORMED_JWT)
        }
    }

    private fun numericDate(value: Any?): Instant {
        if (value !is Number) invalid()
        val decimal = value.toDouble()
        if (!decimal.isFinite() || decimal != value.toLong().toDouble() ||
            value.toLong() !in -62135596800L..253402300799L) invalid()
        return try { Instant.ofEpochSecond(value.toLong()) } catch (_: RuntimeException) { invalid() }
    }

    private fun invalid(): Nothing = throw BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN)
}
