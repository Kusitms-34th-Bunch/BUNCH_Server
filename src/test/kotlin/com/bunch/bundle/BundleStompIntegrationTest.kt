package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleTextRequest
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.service.BundleService
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"]
)
@ActiveProfiles("test")
class BundleStompIntegrationTest : BunchIntegrationSupport() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired private lateinit var bundles: BundleService
    @Autowired private lateinit var tokenProvider: TokenProvider
    @Autowired private lateinit var sessions: RefreshTokenRepository
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var assets: ImageAssetRepository
    @Autowired private lateinit var photoRows: PhotoRepository
    @Autowired private lateinit var uploaderRows: PhotoUploaderRepository
    @Autowired private lateinit var bunchRows: BunchRepository

    private val connections = mutableListOf<WireClient>()
    private val credentials = mutableListOf<Credential>()
    private val ownAssets = mutableListOf<Long>()
    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun closeRealtimeFixtures() {
        connections.forEach { it.close() }
        credentials.forEach { sessions.delete(it.sid, it.memberId) }
        jdbc.update("DELETE FROM bundle_edit_leases")
        jdbc.update("DELETE FROM bundle_comments")
        jdbc.update("DELETE FROM bundle_participants")
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundle_requests")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        ownAssets.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
    }

    @Test
    fun `two connections keep lease tokens private and deliver committed values with reconnect recovery`() {
        val fixture = fixture()
        val owner = connect(fixture.owner)
        val guest = connect(fixture.guest)
        subscribe(owner, fixture)
        subscribe(guest, fixture)
        owner.clear()
        guest.clear()

        owner.send(command(fixture, "lease/acquire"), mapOf("target" to "TITLE", "screenId" to "owner-screen"))
        val grant = owner.json { it.path("type").asText() == "LEASE_GRANTED" }
        assertThat(grant.path("revision").asLong()).isEqualTo(fixture.revision)
        val publicLease = guest.json { it.path("type").asText() == "LEASES" }
        assertThat(publicLease.toString()).doesNotContain("token", "sessionId", "screenId")
        assertThat(publicLease.path("data").path("states").single().path("memberId").asLong()).isEqualTo(fixture.owner.memberId)
        assertThat(guest.frames.poll(200, TimeUnit.MILLISECONDS)?.body.orEmpty()).doesNotContain("token")

        val token = grant.path("data").path("token").asText()
        guest.send(command(fixture, "lease/acquire"), mapOf("target" to "TITLE", "screenId" to "guest-screen"))
        val rejection = guest.json { it.path("type").asText() == "COMMAND_REJECTED" }
        assertThat(rejection.path("data").path("code").asText()).isEqualTo("BUNDLE_004")
        assertThat(rejection.toString()).doesNotContain(token, "sessionId")
        val requestId = UUID.randomUUID().toString()
        val saved = putTitle(fixture, token, "실제 저장된 제목 👨‍👩‍👧‍👦", requestId)
        val event = guest.json { it.path("type").asText() == "TITLE" }
        assertThat(event.path("data").path("value").asText()).isEqualTo("실제 저장된 제목 👨‍👩‍👧‍👦")
        assertThat(event.path("data").path("version").asLong()).isEqualTo(1)
        assertThat(event.path("requestId").asText()).isEqualTo(requestId)
        assertThat(event.path("revision").asLong()).isEqualTo(saved.path("revision").asLong())
        assertThat(event.path("revision").asLong()).isEqualTo(fixture.revision + 1)

        owner.send(command(fixture, "lease/heartbeat"), mapOf(
            "target" to "TITLE", "screenId" to "owner-screen", "token" to token
        ))
        val heartbeat = owner.json { it.path("type").asText() == "LEASE_HEARTBEAT" }
        assertThat(heartbeat.path("revision").asLong()).isEqualTo(saved.path("revision").asLong())
        assertThat(heartbeat.path("data").path("expiresAt").asText()).isNotBlank()

        guest.close()
        // Simulate a missed event while the view is disconnected. No replay log is used.
        val later = putTitle(fixture, token, "연결이 끊긴 동안 저장", UUID.randomUUID().toString(), 1)
        val reconnected = connect(fixture.guest)
        subscribe(reconnected, fixture)
        reconnected.send(command(fixture, "activity"), mapOf("type" to "VIEW", "screenId" to "guest-screen"))
        val recoveredHeartbeat = reconnected.json { it.path("type").asText() == "ACTIVITY" && it.path("data").path("activity").asText() == "VIEW" }
        assertThat(recoveredHeartbeat.path("revision").asLong()).isEqualTo(later.path("revision").asLong())
        owner.send(command(fixture, "lease/release"), mapOf(
            "target" to "TITLE", "screenId" to "owner-screen", "token" to token
        ))
        val release = owner.json { it.path("type").asText() == "LEASE_RELEASED" }
        assertThat(release.path("data").path("target").asText()).isEqualTo("TITLE")
        assertThat(release.path("revision").asLong()).isEqualTo(later.path("revision").asLong())
        val snapshot = getSnapshot(fixture, fixture.guest)
        assertThat(snapshot.path("title").asText()).isEqualTo("연결이 끊긴 동안 저장")
        assertThat(snapshot.path("revision").asLong()).isEqualTo(later.path("revision").asLong())

        reconnected.clear()
        bundles.delete(actor(fixture.owner), fixture.bunchId, fixture.bundleId, UUID.randomUUID().toString())
        assertThat(reconnected.json { it.path("type").asText() == "DELETED" }.path("data").isNull).isTrue()
    }

    @Test
    fun `same member second connection never receives another connection lease grant`() {
        val fixture = fixture()
        val first = connect(fixture.owner)
        val second = connect(fixture.owner)
        subscribe(first, fixture)
        subscribe(second, fixture)
        first.clear()
        second.clear()
        first.send(command(fixture, "lease/acquire"), mapOf("target" to "DATE", "screenId" to "first-screen"))
        assertThat(first.json { it.path("type").asText() == "LEASE_GRANTED" }.path("data").path("target").asText()).isEqualTo("DATE")
        val publicState = second.json { it.path("type").asText() == "LEASES" }
        assertThat(publicState.toString()).doesNotContain("token")
        second.assertNoEvent("LEASE_GRANTED")
        first.close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (bundles.get(fixture.owner.memberId, fixture.bunchId, fixture.bundleId).leases.isNotEmpty()) {
            check(System.nanoTime() < deadline) { "Disconnected connection lease was not released" }
            Thread.sleep(20)
        }
        second.send(command(fixture, "lease/acquire"), mapOf("target" to "DATE", "screenId" to "second-screen"))
        val reacquired = second.json { it.path("type").asText() == "LEASE_GRANTED" }
        assertThat(reacquired.path("data").path("leaseVersion").asLong()).isGreaterThan(1)
    }

    @Test
    fun `existing subscriptions lose delivery after logout or membership loss and cannot publish broker messages`() {
        val fixture = fixture()
        val owner = connect(fixture.owner)
        val guest = connect(fixture.guest)
        subscribe(owner, fixture)
        subscribe(guest, fixture)
        owner.clear()
        guest.clear()

        guest.sendRaw("SEND\ndestination:${topic(fixture)}\ncontent-type:application/json\n\n" +
            "{\"bunchId\":${fixture.bunchId},\"bundleId\":${fixture.bundleId},\"type\":\"FORGED\"}\u0000")
        guest.send(command(fixture, "activity"), mapOf("type" to "VIEW", "screenId" to "guest"))
        guest.json { it.path("type").asText() == "ACTIVITY" && it.path("data").path("activity").asText() == "VIEW" }
        assertThat(owner.frames.toList().none { it.body.contains("FORGED") }).isTrue()
        owner.clear()
        guest.clear()

        sessions.delete(fixture.guest.sid, fixture.guest.memberId)
        bundles.comment(actor(fixture.owner), fixture.bunchId, fixture.bundleId,
            BundleTextRequest("로그아웃 뒤 변경", 0, BundleStatus.DRAFT), "logout-change")
        owner.json { it.path("type").asText() == "COMMENT" }
        guest.assertNoEvent("COMMENT")

        val thirdCredential = credential(fixture.guest.memberId)
        val third = connect(thirdCredential)
        subscribe(third, fixture)
        third.clear()
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ? AND member_id = ?",
            fixture.bunchId, fixture.guest.memberId)
        bundles.comment(actor(fixture.owner), fixture.bunchId, fixture.bundleId,
            BundleTextRequest("번치 이탈 뒤 변경", 1, BundleStatus.DRAFT), "membership-change")
        owner.json { it.path("type").asText() == "COMMENT" }
        third.assertNoEvent("COMMENT")
    }

    @Test
    fun `origin URL credentials and unauthenticated connect are rejected and foreign subscriptions receive no data`() {
        assertThatThrownBy {
            http.newWebSocketBuilder().header("Origin", "https://untrusted.example")
                .buildAsync(URI("ws://localhost:$port/ws"), WireClient()).get(3, TimeUnit.SECONDS)
        }.hasCauseInstanceOf(java.net.http.WebSocketHandshakeException::class.java)
        assertThatThrownBy {
            http.newWebSocketBuilder().header("Origin", "http://localhost:3000")
                .buildAsync(URI("ws://localhost:$port/ws?token=fake-test-value"), WireClient()).get(3, TimeUnit.SECONDS)
        }.hasCauseInstanceOf(java.net.http.WebSocketHandshakeException::class.java)
        val noCredentials = rawConnection()
        noCredentials.sendRaw("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\u0000")
        assertThat(noCredentials.frames.poll(400, TimeUnit.MILLISECONDS)?.command).isNotEqualTo("CONNECTED")

        val fixture = fixture()
        val outsider = credential(member())
        val wire = connect(outsider)
        wire.subscribe(topic(fixture), "foreign")
        val owner = connect(fixture.owner)
        subscribe(owner, fixture)
        bundles.comment(actor(fixture.owner), fixture.bunchId, fixture.bundleId,
            BundleTextRequest("외부인에게 비노출", 0, BundleStatus.DRAFT), "foreign-change")
        owner.json { it.path("type").asText() == "COMMENT" }
        assertThat(wire.frames.poll(400, TimeUnit.MILLISECONDS)).isNull()
    }

    private fun fixture(): Fixture {
        val owner = credential(member())
        val guest = credential(member())
        val bunchId = create(owner.memberId).id
        join(guest.memberId, issue(owner.memberId, bunchId))
        val asset = assets.saveAndFlush(ImageAsset(publicId = "stomp/${UUID.randomUUID()}", url = "https://example.test/p.jpg"))
        ownAssets.add(asset.id)
        val photo = photoRows.saveAndFlush(Photo(
            bunch = bunchRows.findById(bunchId).orElseThrow(), imageAsset = asset,
            fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2), processingStatus = ProcessingStatus.READY
        ))
        listOf(owner, guest).forEach { uploaderRows.saveAndFlush(PhotoUploader(
            photo = photo, member = members.findById(it.memberId).orElseThrow(), uploadBatchId = UUID.randomUUID()
        )) }
        val bundle = bundles.create(actor(owner), bunchId, UUID.randomUUID(), CreateBundleRequest(listOf(photo.id)))
        return Fixture(owner, guest, bunchId, bundle.id, bundle.revision)
    }

    private fun credential(memberId: Long): Credential {
        val pair = tokenProvider.generateToken(memberId, "ROLE_USER")
        val sid = tokenProvider.getSessionId(pair.accessToken)
        sessions.save(sid, memberId, pair.refreshToken, tokenProvider.getRefreshTokenValiditySeconds())
        return Credential(memberId, sid, pair.accessToken).also(credentials::add)
    }

    private fun actor(value: Credential) = BundleActor(value.memberId, value.sid, "owner-screen")
    private fun topic(fixture: Fixture) = "/topic/bunches/${fixture.bunchId}/bundles/${fixture.bundleId}"
    private fun command(fixture: Fixture, suffix: String) =
        "/app/bunches/${fixture.bunchId}/bundles/${fixture.bundleId}/$suffix"

    private fun rawConnection(): WireClient = WireClient().also { client ->
        client.socket = http.newWebSocketBuilder().header("Origin", "http://localhost:3000")
            .buildAsync(URI("ws://localhost:$port/ws"), client).get(5, TimeUnit.SECONDS)
        connections.add(client)
    }

    private fun connect(credential: Credential): WireClient = rawConnection().also {
        it.sendRaw("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer ${credential.access}\n\n\u0000")
        assertThat(it.frame { frame -> frame.command == "CONNECTED" }.command).isEqualTo("CONNECTED")
    }

    private fun subscribe(client: WireClient, fixture: Fixture) {
        client.subscribe(topic(fixture), "topic")
        client.subscribe("/user/queue/bundle-leases", "leases")
        client.subscribe("/user/queue/bundle-activity", "activity")
        client.send(command(fixture, "activity"), mapOf("type" to "VIEW", "screenId" to "ready"))
        client.json { it.path("type").asText() == "ACTIVITY" && it.path("data").path("activity").asText() == "VIEW" }
        client.clear()
    }

    private fun putTitle(fixture: Fixture, token: String, value: String, requestId: String, version: Long = 0): JsonNode {
        val body = mapper.writeValueAsString(mapOf(
            "value" to value, "expectedVersion" to version, "mode" to "DRAFT", "leaseToken" to token
        ))
        val response = http.send(HttpRequest.newBuilder(URI(
            "http://localhost:$port/api/v1/bunches/${fixture.bunchId}/bundles/${fixture.bundleId}/title"
        )).header("Authorization", "Bearer ${fixture.owner.access}")
            .header("Content-Type", "application/json").header("X-Bundle-Screen", "owner-screen")
            .header("X-Request-Id", requestId).PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        return mapper.readTree(response.body()).path("data")
    }

    private fun getSnapshot(fixture: Fixture, credential: Credential): JsonNode {
        val response = http.send(HttpRequest.newBuilder(URI(
            "http://localhost:$port/api/v1/bunches/${fixture.bunchId}/bundles/${fixture.bundleId}"
        )).header("Authorization", "Bearer ${credential.access}").GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(200)
        return mapper.readTree(response.body()).path("data")
    }

    private data class Credential(val memberId: Long, val sid: String, val access: String)
    private data class Fixture(
        val owner: Credential, val guest: Credential, val bunchId: Long, val bundleId: Long, val revision: Long
    )
    private data class Frame(val command: String, val headers: Map<String, String>, val body: String)

    private inner class WireClient : WebSocket.Listener {
        lateinit var socket: WebSocket
        val frames = LinkedBlockingQueue<Frame>()
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }

        @Synchronized
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            buffer.append(data)
            var end = buffer.indexOf("\u0000")
            while (end >= 0) {
                val raw = buffer.substring(0, end).trimStart('\n', '\r')
                buffer.delete(0, end + 1)
                val parts = raw.split("\n\n", limit = 2)
                val lines = parts.first().split('\n')
                frames.add(Frame(lines.first(), lines.drop(1).associate {
                    it.substringBefore(':') to it.substringAfter(':')
                }, parts.getOrElse(1) { "" }))
                end = buffer.indexOf("\u0000")
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        fun sendRaw(value: String) { socket.sendText(value, true).get(3, TimeUnit.SECONDS) }
        fun send(destination: String, value: Any) {
            sendRaw("SEND\ndestination:$destination\ncontent-type:application/json\n\n${mapper.writeValueAsString(value)}\u0000")
        }
        fun subscribe(destination: String, id: String) {
            sendRaw("SUBSCRIBE\nid:$id\ndestination:$destination\nack:auto\n\n\u0000")
        }
        fun json(predicate: (JsonNode) -> Boolean): JsonNode = mapper.readTree(frame {
            if (it.command != "MESSAGE") false else {
                val message = mapper.readTree(it.body)
                assertThat(message.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
                    "type", "bunchId", "bundleId", "revision", "requestId", "data"
                )
                assertThat(BundleEventType.entries.map { type -> type.name }).contains(message.path("type").asText())
                predicate(message)
            }
        }.body)
        fun frame(predicate: (Frame) -> Boolean): Frame {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val frame = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (predicate(frame)) return frame
            }
            throw AssertionError("Expected STOMP frame was not delivered within five seconds")
        }
        fun assertNoEvent(type: String) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
            while (System.nanoTime() < deadline) {
                val frame = frames.poll(50, TimeUnit.MILLISECONDS) ?: continue
                val actual = runCatching { mapper.readTree(frame.body).path("type").asText() }.getOrDefault("")
                assertThat(actual).isNotEqualTo(type)
            }
        }
        fun clear() { frames.clear() }
        fun close() { if (::socket.isInitialized) socket.abort() }
    }
}
