package com.bunch.global.config

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0", "springdoc.api-docs.enabled=false"]
)
@ActiveProfiles("test")
@AutoConfigureObservability
class ManagementHttpIntegrationTest @Autowired constructor(
    private val http: TestRestTemplate,
    private val tokens: TokenProvider,
    @LocalServerPort private val appPort: Int,
    @Value("\${local.management.port}") private val managementPort: Int
) {
    @MockitoBean
    private lateinit var sessions: RefreshTokenRepository

    @Test
    fun `health and prometheus are public on the management port without JWT session checks`() {
        assertThat(managementPort).isNotEqualTo(appPort)
        val headers = HttpHeaders().apply { setBearerAuth("invalid-test-token") }

        val health = http.exchange(
            url(managementPort, "/actuator/health"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java
        )
        assertThat(health.statusCode.value()).isEqualTo(200)
        assertThat(health.body).isEqualTo("{\"status\":\"UP\"}")

        val metrics = http.getForEntity(url(managementPort, "/actuator/prometheus"), String::class.java)
        assertThat(metrics.statusCode.value()).isEqualTo(200)
        assertThat(metrics.body).contains("jvm_memory_used_bytes", "hikaricp_connections")
        verifyNoInteractions(sessions)
    }

    @Test
    fun `management data stays unavailable on the app port even for authenticated users`() {
        val token = tokens.generateToken(42, "ROLE_USER").accessToken
        `when`(sessions.isActive(anyString(), anyLong())).thenReturn(true)

        listOf("/actuator/health", "/actuator/prometheus").forEach { path ->
            val anonymous = http.getForEntity(url(appPort, path), String::class.java)
            assertThat(anonymous.statusCode.is4xxClientError).isTrue()

            val authenticated = http.exchange(
                url(appPort, path),
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(token) }),
                String::class.java
            )
            assertThat(authenticated.statusCode.is4xxClientError).isTrue()
            assertThat(authenticated.body.orEmpty()).doesNotContain("jvm_memory", "\"status\":\"UP\"")
        }
    }

    @Test
    fun `management port denies other endpoints and app API remains protected`() {
        listOf("/actuator", "/actuator/env", "/actuator/metrics", "/api/v1/members/me").forEach { path ->
            val response = http.getForEntity(url(managementPort, path), String::class.java)
            assertThat(response.statusCode.is4xxClientError).isTrue()
        }
        val protected = http.getForEntity(url(appPort, "/api/v1/members/me"), String::class.java)
        assertThat(protected.statusCode.value()).isEqualTo(401)
        assertThat(protected.body).contains("AUTH_001")
    }

    @Test
    fun `HTTP histogram uses invitation route template without capability tokens`() {
        val first = "monitoring-secret-invitation-one"
        val second = "monitoring-secret-invitation-two"
        val client = HttpClient.newHttpClient()
        listOf(first, second).forEach { token ->
            client.send(
                HttpRequest.newBuilder(URI.create(url(appPort, "/api/v1/invitations/$token"))).GET().build(),
                HttpResponse.BodyHandlers.discarding()
            )
        }

        val metrics = http.getForObject(url(managementPort, "/actuator/prometheus"), String::class.java)
        assertThat(metrics)
            .contains("http_server_requests_seconds_bucket", "/api/v1/invitations/{token}")
            .doesNotContain(first, second)
    }

    private fun url(port: Int, path: String): String = "http://localhost:$port$path"
}
