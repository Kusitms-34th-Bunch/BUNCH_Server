package com.bunch.global.oidc

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * Cancels the Sign in with Apple connection of a member who withdraws (App Store guideline 5.1.1(v)).
 * The server keeps no Apple token, so the app sends a fresh authorization code: it is exchanged for the
 * member's tokens and the refresh token is revoked.
 */
@Component
class AppleTokenRevoker(
    private val secrets: AppleClientSecret,
    @Value("\${auth.apple.client-id}")
    private val clientId: String,
    @Value("\${auth.apple.token-uri:https://appleid.apple.com/auth/token}")
    private val tokenUri: String = "https://appleid.apple.com/auth/token",
    @Value("\${auth.apple.revoke-uri:https://appleid.apple.com/auth/revoke}")
    private val revokeUri: String = "https://appleid.apple.com/auth/revoke",
    private val client: RestClient = restClient()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** @param expectedSubject the Apple id the member signed in with, so a code of another Apple account is refused. */
    fun revoke(authorizationCode: String, expectedSubject: String) {
        try {
            val secret = secrets.create()
            val tokens = exchange(authorizationCode, secret)
            check(subjectOf(tokens) == expectedSubject) { "The code belongs to another Apple account." }
            revokeToken(tokens, secret)
        } catch (exception: Exception) {
            // The code, the secret and Apple's answer are never logged.
            logger.warn("Apple token revocation failed: {}", exception.javaClass.simpleName)
            throw BusinessException(ErrorCode.APPLE_REVOKE_FAILED)
        }
    }

    private fun exchange(authorizationCode: String, secret: String): JsonNode {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", authorizationCode)
            add("client_id", clientId)
            add("client_secret", secret)
        }
        return post(tokenUri, form)?.takeIf { it.isObject } ?: error("Apple gave no tokens.")
    }

    private fun revokeToken(tokens: JsonNode, secret: String) {
        val refresh = tokens.path("refresh_token").asText("")
        val token = refresh.ifBlank { tokens.path("access_token").asText("") }
        check(token.isNotBlank()) { "Apple gave no token to revoke." }
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", clientId)
            add("client_secret", secret)
            add("token", token)
            add("token_type_hint", if (refresh.isNotBlank()) "refresh_token" else "access_token")
        }
        post(revokeUri, form)
    }

    // The id token came straight from Apple over TLS as the answer to this request, so only its subject is read.
    private fun subjectOf(tokens: JsonNode): String? =
        SignedJWT.parse(tokens.path("id_token").asText("")).jwtClaimsSet.subject

    private fun post(uri: String, form: LinkedMultiValueMap<String, String>): JsonNode? =
        client.post().uri(uri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .onStatus({ !it.is2xxSuccessful }) { _, response ->
                throw IllegalStateException("Apple answered ${response.statusCode.value()}.")
            }
            .body(JsonNode::class.java)
}

private fun restClient(): RestClient = RestClient.builder()
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
