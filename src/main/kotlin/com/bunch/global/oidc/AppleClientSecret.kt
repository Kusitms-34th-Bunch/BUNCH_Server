package com.bunch.global.oidc

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * The client secret Apple asks for when the server calls its token endpoints: a short-lived JWT signed
 * with the Sign in with Apple key (ES256). It is made for each call, so nothing is cached or stored.
 */
@Component
class AppleClientSecret(
    @Value("\${auth.apple.client-id}")
    private val clientId: String,
    @Value("\${auth.apple.team-id:}")
    private val teamId: String = "",
    @Value("\${auth.apple.key-id:}")
    private val keyId: String = "",
    @Value("\${auth.apple.private-key:}")
    private val privateKey: String = ""
) {
    fun create(now: Instant = Instant.now()): String {
        check(teamId.isNotBlank() && keyId.isNotBlank() && privateKey.isNotBlank()) {
            "Apple team id, key id and private key are not configured."
        }
        return Jwts.builder()
            .setHeaderParam("kid", keyId)
            .setIssuer(teamId)
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(now.plus(VALIDITY)))
            .setAudience(AUDIENCE)
            .setSubject(clientId)
            .signWith(parse(privateKey), SignatureAlgorithm.ES256)
            .compact()
    }

    // The .p8 file is a PKCS#8 PEM. In an env file it is one line, so a literal "\n" stands for a line break.
    private fun parse(pem: String): PrivateKey {
        val body = pem.replace("\\n", "\n").lines()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .filterNot { it.isWhitespace() }
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
    }

    companion object {
        const val AUDIENCE = "https://appleid.apple.com"
        private val VALIDITY: Duration = Duration.ofMinutes(5)
    }
}
