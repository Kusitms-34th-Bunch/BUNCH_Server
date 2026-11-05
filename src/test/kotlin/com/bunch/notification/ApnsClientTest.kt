package com.bunch.notification

import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.push.PushDeviceEnvironment
import com.bunch.notification.dto.PushAlert
import com.bunch.notification.dto.PushAps
import com.bunch.notification.dto.PushPayload
import com.bunch.notification.service.ApnsClient
import com.bunch.notification.service.PushApnsSettings
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

class ApnsClientTest {
    @Test
    fun `request signs ES256 provider token and uses the job payload and APNs headers`() = fixture { f ->
        val id = UUID.randomUUID()
        val result = f.send(id)
        val request = f.requests.remove()

        assertThat(result.status).isEqualTo(200)
        assertThat(request.path).isEqualTo("/3/device/aabbccdd")
        assertThat(request.headers["Apns-topic"]).containsExactly("com.bunch.test")
        assertThat(request.headers["Apns-push-type"]).containsExactly("alert")
        assertThat(request.headers["Apns-priority"]).containsExactly("10")
        assertThat(request.headers["Apns-id"]).containsExactly(id.toString())
        assertThat(request.headers["Apns-expiration"])
            .containsExactly(f.now.get().plus(Duration.ofHours(24)).epochSecond.toString())
        val token = request.headers.getValue("Authorization").single().removePrefix("bearer ")
        val jwt = Jwts.parserBuilder().setSigningKey(f.keys.public).build().parseClaimsJws(token)
        assertThat(jwt.header.algorithm).isEqualTo("ES256")
        assertThat(jwt.header.keyId).isEqualTo("KEY1")
        assertThat(jwt.body.issuer).isEqualTo("TEAM1")
        assertThat(f.mapper.readTree(request.body).path("eventId").asText()).isEqualTo(id.toString())
    }

    @Test
    fun `provider tokens are reused and expired response rotates only after twenty minutes`() = fixture { f ->
        f.send()
        val first = f.requests.remove().headers.getValue("Authorization").single()
        f.status.set(403)
        f.body.set("""{"reason":"ExpiredProviderToken"}""")
        f.now.set(f.now.get().plusSeconds(60))
        f.send()
        f.requests.remove()
        f.status.set(200)
        f.send()
        assertThat(f.requests.remove().headers.getValue("Authorization").single()).isEqualTo(first)

        f.status.set(403)
        f.now.set(f.now.get().plus(Duration.ofMinutes(20)))
        assertThat(f.send().reason).isEqualTo("ExpiredProviderToken")
        f.requests.remove()
        f.status.set(200)
        f.send()
        assertThat(f.requests.remove().headers.getValue("Authorization").single()).isNotEqualTo(first)
    }

    @Test
    fun `retry after and permanent response are reported without exposing response bodies`() = fixture { f ->
        f.status.set(429)
        f.body.set("""{"reason":"TooManyRequests"}""")
        f.retryAfter.set("1800")
        val retry = f.send()
        assertThat(retry.status).isEqualTo(429)
        assertThat(retry.retryAfter).isEqualTo(f.now.get().plusSeconds(1800))
        f.status.set(410)
        f.body.set("""{"reason":"Unregistered","timestamp":1234}""")
        assertThat(f.send().reason).isEqualTo("Unregistered")
        f.body.set("""{"reason":"invalid secret response with spaces"}""")
        assertThat(f.send().reason).isEqualTo("UnexpectedResponse")
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            test(fixture)
        } finally {
            fixture.server.stop(0)
        }
    }

    private class Fixture {
        val mapper = ObjectMapper().findAndRegisterModules()
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val now = AtomicReference(Instant.now().minusSeconds(60))
        val status = AtomicReference(200)
        val body = AtomicReference("")
        val retryAfter = AtomicReference<String?>(null)
        val requests = LinkedBlockingQueue<CapturedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/3/device/") { exchange ->
                requests.add(CapturedRequest(exchange.requestURI.path, exchange.requestHeaders, exchange.requestBody.readBytes()))
                retryAfter.get()?.let { exchange.responseHeaders.add("retry-after", it) }
                val bytes = body.get().toByteArray()
                exchange.sendResponseHeaders(status.get(), if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        private val endpoint = "http://127.0.0.1:${server.address.port}"
        private val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now.get()
        }
        private val client = ApnsClient(
            PushApnsSettings(
                "TEAM1", "KEY1", Base64.getEncoder().encodeToString(keys.private.encoded),
                "com.bunch.test", "com.bunch.test", endpoint, endpoint
            ),
            mapper,
            clock
        )

        fun send(id: UUID = UUID.randomUUID()) = client.send(
            PushDeviceEnvironment.SANDBOX,
            "com.bunch.test",
            "aabbccdd",
            id,
            PushPayload(
                PushAps(PushAlert("제목", "내용")), id, NotificationType.MEMBER_JOINED,
                mapOf("kind" to "MEMBERS", "bunchId" to 1)
            ),
            now.get().plus(Duration.ofHours(24))
        )
    }

    private data class CapturedRequest(val path: String, val headers: Map<String, List<String>>, val body: ByteArray)
}
