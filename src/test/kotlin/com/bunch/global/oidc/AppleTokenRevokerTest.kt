package com.bunch.global.oidc

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Cancelling the Apple connection of a member who withdraws (App Store guideline 5.1.1(v)). */
class AppleTokenRevokerTest {
    private val tokenUri = "https://apple.test/auth/token"
    private val revokeUri = "https://apple.test/auth/revoke"
    private lateinit var server: MockRestServiceServer

    private fun pem(): String {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----"
    }

    private fun revoker(privateKey: String = pem()): AppleTokenRevoker {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        val secret = AppleClientSecret("com.bunch.app", "TEAM123456", "KEY1234567", privateKey)
        return AppleTokenRevoker(secret, "com.bunch.app", tokenUri, revokeUri, builder.build())
    }

    // Apple's id token is only read for its subject, so any well-formed signed token will do here.
    private fun idToken(subject: String): String = Jwts.builder().setSubject(subject)
        .signWith(Keys.secretKeyFor(SignatureAlgorithm.HS256)).compact()

    private fun tokens(subject: String = "apple-001", refresh: String? = "refresh-1"): String {
        val refreshPart = refresh?.let { ""","refresh_token":"$it"""" } ?: ""
        return """{"access_token":"access-1"$refreshPart,"id_token":"${idToken(subject)}"}"""
    }

    private fun expectExchange(answer: String) {
        server.expect(requestTo(tokenUri))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().formDataContains(
                    mapOf(
                        "grant_type" to "authorization_code",
                        "code" to "code-1",
                        "client_id" to "com.bunch.app"
                    )
                )
            )
            .andExpect(content().string(containsString("client_secret=")))
            .andRespond(withSuccess(answer, MediaType.APPLICATION_JSON))
    }

    private fun failure(block: () -> Unit): ErrorCode {
        val error = catchThrowable(block)
        assertThat(error).isInstanceOf(BusinessException::class.java)
        return (error as BusinessException).errorCode
    }

    @Test
    fun `the code is exchanged for the tokens and the refresh token is revoked`() {
        val revoker = revoker()
        expectExchange(tokens())
        server.expect(requestTo(revokeUri))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().formDataContains(
                    mapOf(
                        "client_id" to "com.bunch.app",
                        "token" to "refresh-1",
                        "token_type_hint" to "refresh_token"
                    )
                )
            )
            .andExpect(content().string(containsString("client_secret=")))
            .andRespond(withSuccess())

        revoker.revoke("code-1", "apple-001")

        server.verify()
    }

    @Test
    fun `the access token is revoked when Apple gives no refresh token`() {
        val revoker = revoker()
        expectExchange(tokens(refresh = null))
        server.expect(requestTo(revokeUri))
            .andExpect(
                content().formDataContains(mapOf("token" to "access-1", "token_type_hint" to "access_token"))
            )
            .andRespond(withSuccess())

        revoker.revoke("code-1", "apple-001")

        server.verify()
    }

    @Test
    fun `a code of another Apple account is refused and nothing is revoked`() {
        val revoker = revoker()
        expectExchange(tokens(subject = "someone-else"))

        assertThat(failure { revoker.revoke("code-1", "apple-001") }).isEqualTo(ErrorCode.APPLE_REVOKE_FAILED)

        server.verify()
    }

    @Test
    fun `an answer of Apple that is not a success on the exchange stops the revocation`() {
        val revoker = revoker()
        server.expect(requestTo(tokenUri))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""{"error":"invalid_grant"}"""))

        assertThat(failure { revoker.revoke("code-1", "apple-001") }).isEqualTo(ErrorCode.APPLE_REVOKE_FAILED)

        server.verify()
    }

    @Test
    fun `an answer of Apple that is not a success on the revocation is a failure`() {
        val revoker = revoker()
        expectExchange(tokens())
        server.expect(requestTo(revokeUri)).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertThat(failure { revoker.revoke("code-1", "apple-001") }).isEqualTo(ErrorCode.APPLE_REVOKE_FAILED)

        server.verify()
    }

    @Test
    fun `tokens without an id token or without a token to revoke are a failure`() {
        val revoker = revoker()
        expectExchange("""{"access_token":"access-1"}""")

        assertThat(failure { revoker.revoke("code-1", "apple-001") }).isEqualTo(ErrorCode.APPLE_REVOKE_FAILED)

        server.verify()
    }

    @Test
    fun `a key that is not configured fails without calling Apple`() {
        val revoker = revoker(privateKey = "")

        assertThat(failure { revoker.revoke("code-1", "apple-001") }).isEqualTo(ErrorCode.APPLE_REVOKE_FAILED)

        server.verify()
    }
}
