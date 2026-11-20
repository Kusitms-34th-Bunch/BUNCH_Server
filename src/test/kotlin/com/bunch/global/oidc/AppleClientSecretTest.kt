package com.bunch.global.oidc

import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date

/** The client secret Apple asks for on its token endpoints (App Store guideline 5.1.1(v)). */
class AppleClientSecretTest {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun pem(): String = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
        "\n-----END PRIVATE KEY-----\n"

    private fun secret(privateKey: String = pem(), teamId: String = "TEAM123456", keyId: String = "KEY1234567") =
        AppleClientSecret("com.bunch.app", teamId, keyId, privateKey)

    @Test
    fun `the secret is signed with ES256 and carries the key id`() {
        val jws = Jwts.parserBuilder().setSigningKey(pair.public).build().parseClaimsJws(secret().create())

        assertThat(jws.header.algorithm).isEqualTo("ES256")
        assertThat(jws.header["kid"]).isEqualTo("KEY1234567")
    }

    @Test
    fun `the claims name the team, the app and Apple as the audience`() {
        val now = Instant.parse("2026-11-19T00:00:00Z")

        val claims = Jwts.parserBuilder().setSigningKey(pair.public).setClock { Date.from(now) }.build()
            .parseClaimsJws(secret().create(now)).body

        assertThat(claims.issuer).isEqualTo("TEAM123456")
        assertThat(claims.subject).isEqualTo("com.bunch.app")
        assertThat(claims.audience).isEqualTo("https://appleid.apple.com")
        assertThat(claims.issuedAt).isEqualTo(Date.from(now))
        assertThat(claims.expiration).isEqualTo(Date.from(now.plusSeconds(300)))
    }

    @Test
    fun `a key written on one line with escaped line breaks works as the file does`() {
        val oneLine = pem().trim().replace("\n", "\\n")

        val token = secret(oneLine).create()

        assertThat(Jwts.parserBuilder().setSigningKey(pair.public).build().parseClaimsJws(token)).isNotNull
    }

    @Test
    fun `a secret that is not configured is refused before anything is signed`() {
        assertThatThrownBy { secret(privateKey = "").create() }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { secret(teamId = "").create() }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { secret(keyId = "").create() }.isInstanceOf(IllegalStateException::class.java)
    }
}
