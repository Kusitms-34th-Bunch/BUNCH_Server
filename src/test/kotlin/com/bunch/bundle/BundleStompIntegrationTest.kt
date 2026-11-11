package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.bundle.service.BundlePresenceService
import org.springframework.context.ApplicationEventPublisher
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleVisibilityRequest
import com.bunch.bundle.dto.PublishBundleRequest
import com.bunch.bundle.dto.BundleTextRequest
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.dto.BundleLocationInput
import com.bunch.bundle.dto.BundleLocationsRequest
import com.bunch.bundle.service.BundleLocationService
import com.bunch.bundle.service.BundleService
import com.bunch.global.exception.ErrorCode
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.time.Instant
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

    @Autowired private lateinit var presence: BundlePresenceService
    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var bundles: BundleService
    @Autowired private lateinit var locations: BundleLocationService
    @Autowired private lateinit var tokenProvider: TokenProvider
    @Autowired private lateinit var sessions: RefreshTokenRepository
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var assets: ImageAssetRepository
    @Autowired private lateinit var photoRows: PhotoRepository
    @Autowired private lateinit var uploaderRows: PhotoUploaderRepository
    @Autowired private lateinit var bunchRows: BunchRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

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

        owner.send(command(fixture, "lease/acquire"), mapOf("target" to "TITLE"))
        val grant = owner.json { it.path("type").asText() == "LEASE_GRANTED" }
        assertThat(grant.path("revision").asLong()).isEqualTo(fixture.revision)
        val publicLease = guest.json { it.path("type").asText() == "LEASES" }
        assertThat(publicLease.toString()).doesNotContain("token", "sessionId", "connectionId")
        assertThat(publicLease.path("data").path("states").single().path("memberId").asLong()).isEqualTo(fixture.owner.memberId)
        assertThat(guest.frames.poll(200, TimeUnit.MILLISECONDS)?.body.orEmpty()).doesNotContain("token")

        val token = grant.path("data").path("token").asText()
        guest.send(command(fixture, "lease/acquire"), mapOf("target" to "TITLE"))
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
            "target" to "TITLE", "token" to token
        ))
        val heartbeat = owner.json { it.path("type").asText() == "LEASE_HEARTBEAT" }
        assertThat(heartbeat.path("revision").asLong()).isEqualTo(saved.path("revision").asLong())
        assertThat(heartbeat.path("data").path("expiresAt").asText()).isNotBlank()

        guest.close()
        // Simulate a missed event while the view is disconnected. No replay log is used.
        val later = putTitle(fixture, token, "연결이 끊긴 동안 저장", UUID.randomUUID().toString(), 1)
        val reconnected = connect(fixture.guest)
        subscribe(reconnected, fixture)
        reconnected.send(command(fixture, "activity"), mapOf("type" to "VIEW"))
        val recoveredHeartbeat = reconnected.json { it.path("type").asText() == "ACTIVITY" && it.path("data").path("activity").asText() == "VIEW" }
        assertThat(recoveredHeartbeat.path("revision").asLong()).isEqualTo(later.path("revision").asLong())
        owner.send(command(fixture, "lease/release"), mapOf(
            "target" to "TITLE", "token" to token
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
        first.send(command(fixture, "lease/acquire"), mapOf("target" to "DATE"))
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
        second.send(command(fixture, "lease/acquire"), mapOf("target" to "DATE"))
        val reacquired = second.json { it.path("type").asText() == "LEASE_GRANTED" }
        assertThat(reacquired.path("data").path("leaseVersion").asLong()).isGreaterThan(1)
    }

    @Test
    fun `same session different connection cannot acquire heartbeat or release another socket lease`() {
        val fixture = fixture()
        val first = connect(fixture.owner)
        val second = connect(fixture.owner)
        subscribe(first, fixture)
        subscribe(second, fixture)
        val photoId = getSnapshot(fixture, fixture.owner).path("photos").first().path("id").asLong()

        listOf("TITLE", "DATE", "PHOTO:$photoId").forEach { target ->
            first.clear()
            second.clear()
            first.send(command(fixture, "lease/acquire"), mapOf("target" to target))
            val grant = first.json { it.path("type").asText() == "LEASE_GRANTED" }
            second.json { it.path("type").asText() == "LEASES" }
            first.clear()
            second.clear()
            val token = grant.path("data").path("token").asText()

            second.send(command(fixture, "lease/acquire"), mapOf("target" to target))
            assertThat(second.json { it.path("type").asText() == "COMMAND_REJECTED" }
                .path("data").path("code").asText()).isEqualTo("BUNDLE_004")
            listOf("heartbeat", "release").forEach { action ->
                second.send(command(fixture, "lease/$action"), mapOf("target" to target, "token" to token))
                assertThat(second.json { it.path("type").asText() == "COMMAND_REJECTED" }
                    .path("data").path("code").asText()).isEqualTo("BUNDLE_004")
            }
            first.send(command(fixture, "lease/heartbeat"), mapOf("target" to target, "token" to token))
            assertThat(first.json { it.path("type").asText() == "LEASE_HEARTBEAT" }
                .path("data").path("token").asText()).isEqualTo(token)
            first.send(command(fixture, "lease/release"), mapOf("target" to target, "token" to token))
            first.json { it.path("type").asText() == "LEASE_RELEASED" }
            second.json { it.path("type").asText() == "LEASES" && it.path("data").path("states").isEmpty }
        }
    }

    @Test
    fun `co owner participant keeps draft rights but published comment activity is rejected`() {
        val fixture = fixture()
        jdbc.update(
            "UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE bunch_id = ? AND member_id = ?",
            fixture.bunchId,
            fixture.guest.memberId
        )
        val owner = connect(fixture.owner)
        val coOwner = connect(fixture.guest)
        subscribe(owner, fixture)
        subscribe(coOwner, fixture)
        owner.clear()
        coOwner.clear()
        assertThat(getSnapshot(fixture, fixture.guest).path("permissions").path("administrator").asBoolean()).isTrue()
        assertThat(getSnapshot(fixture, fixture.guest).path("permissions").path("canEdit").asBoolean()).isTrue()

        coOwner.send(command(fixture, "lease/acquire"), mapOf("target" to "TITLE"))
        val grant = coOwner.json { it.path("type").asText() == "LEASE_GRANTED" }
        owner.json { it.path("type").asText() == "LEASES" }
        bundles.title(
            actor(fixture.guest),
            fixture.bunchId,
            fixture.bundleId,
            BundleTextRequest("공동 관리자 제목", 0, BundleStatus.DRAFT,
                UUID.fromString(grant.path("data").path("token").asText())),
            "co-owner-title"
        )
        bundles.visibility(
            actor(fixture.guest),
            fixture.bunchId,
            fixture.bundleId,
            BundleVisibilityRequest(Visibility.PRIVATE, 0, BundleStatus.DRAFT),
            "co-owner-visibility"
        )
        listOf(fixture.owner, fixture.guest).forEach { author ->
            bundles.comment(
                actor(author),
                fixture.bunchId,
                fixture.bundleId,
                BundleTextRequest("발행 코멘트", 0, BundleStatus.DRAFT),
                "co-owner-comment-${author.memberId}"
            )
        }
        val ready = bundles.get(fixture.guest.memberId, fixture.bunchId, fixture.bundleId)
        val published = bundles.publish(
            actor(fixture.guest),
            fixture.bunchId,
            fixture.bundleId,
            UUID.randomUUID(),
            PublishBundleRequest(ready.revision)
        )
        val event = coOwner.json { it.path("type").asText() == "PUBLISHED" }
        assertThat(event.path("data").path("publishedBy").asLong()).isEqualTo(fixture.guest.memberId)
        assertThat(event.path("data").path("publisherNickname").asText()).isNotBlank()
        assertThat(published.publishedBy).isEqualTo(fixture.guest.memberId)
        owner.json { it.path("type").asText() == "PUBLISHED" }
        assertThat(published.permissions.canEdit).isFalse()
        assertThat(getSnapshot(fixture, fixture.guest).path("status").asText()).isEqualTo("PUBLISHED")
        val activityBefore = jdbc.queryForObject(
            "SELECT last_activity_at FROM bunch_memberships WHERE bunch_id = ? AND member_id = ?",
            java.sql.Timestamp::class.java,
            fixture.bunchId,
            fixture.guest.memberId
        )
        coOwner.clear()
        owner.clear()

        // A real SEND must pass the inbound interceptor as well as the controller check.
        coOwner.send(command(fixture, "activity"), mapOf("type" to "COMMENT"))
        coOwner.assertNoEvent("ACTIVITY")
        owner.assertNoEvent("ACTIVITY")
        coOwner.clear()
        coOwner.send(command(fixture, "lease/acquire"), mapOf("target" to "TITLE"))
        coOwner.assertNoEvent("LEASE_GRANTED")
        assertThat(bundles.get(fixture.guest.memberId, fixture.bunchId, fixture.bundleId).revision)
            .isEqualTo(published.revision)
        assertThat(jdbc.queryForObject(
            "SELECT last_activity_at FROM bunch_memberships WHERE bunch_id = ? AND member_id = ?",
            java.sql.Timestamp::class.java,
            fixture.bunchId,
            fixture.guest.memberId
        )).isEqualTo(activityBefore)
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
        guest.send(command(fixture, "activity"), mapOf("type" to "VIEW"))
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

    @Test
    fun `nonparticipant commands are silently dropped while view activity remains available`() {
        val fixture = fixture()
        val viewerCredential = credential(member())
        join(viewerCredential.memberId, issue(fixture.owner.memberId, fixture.bunchId))
        val viewer = connect(viewerCredential)
        viewer.subscribe("/user/queue/bundle-leases", "leases")
        viewer.subscribe("/user/queue/bundle-activity", "activity")
        viewer.send(command(fixture, "activity"), mapOf("type" to "VIEW"))
        viewer.json { it.path("type").asText() == "ACTIVITY" }

        val rejectedCommands = listOf(
            "lease/acquire" to mapOf("target" to "TITLE"),
            "lease/heartbeat" to mapOf("target" to "TITLE", "token" to UUID.randomUUID()),
            "lease/release" to mapOf("target" to "TITLE", "token" to UUID.randomUUID()),
            "activity" to mapOf("type" to " comment ")
        )
        rejectedCommands.forEach { (suffix, body) ->
            viewer.send(command(fixture, suffix), body)
            assertThat(viewer.frames.poll(500, TimeUnit.MILLISECONDS)).isNull()
        }

        viewer.send(command(fixture, "activity"), mapOf("type" to "VIEW"))
        val response = viewer.json { it.path("type").asText() == "ACTIVITY" }
        assertThat(response.path("data").path("activity").asText()).isEqualTo("VIEW")
        assertThat(getSnapshot(fixture, viewerCredential).path("revision").asLong()).isEqualTo(fixture.revision)
    }

    @Test
    fun `content events wait for commit and rolled back changes never reach subscribers`() {
        val fixture = fixture()
        val guest = connect(fixture.guest)
        guest.subscribe(topic(fixture), "topic")
        guest.send(command(fixture, "activity"), mapOf("type" to "VIEW"))
        guest.json { it.path("type").asText() == "ACTIVITY" }
        val transaction = TransactionTemplate(transactionManager)

        transaction.executeWithoutResult {
            bundles.visibility(
                actor(fixture.owner), fixture.bunchId, fixture.bundleId,
                BundleVisibilityRequest(Visibility.PRIVATE, 0, BundleStatus.DRAFT), "committed-visibility"
            )
            guest.assertNoEvent("VISIBILITY")
        }
        val committed = guest.json { it.path("type").asText() == "VISIBILITY" }
        assertThat(committed.path("requestId").asText()).isEqualTo("committed-visibility")
        assertThat(committed.path("data").path("value").asText()).isEqualTo("PRIVATE")

        transaction.executeWithoutResult { status ->
            bundles.visibility(
                actor(fixture.owner), fixture.bunchId, fixture.bundleId,
                BundleVisibilityRequest(Visibility.PUBLIC, 1, BundleStatus.DRAFT), "rolled-back-visibility"
            )
            status.setRollbackOnly()
        }
        guest.assertNoEvent("VISIBILITY")
        val snapshot = getSnapshot(fixture, fixture.guest)
        assertThat(snapshot.path("visibility").asText()).isEqualTo("PRIVATE")
        assertThat(snapshot.path("revision").asLong()).isEqualTo(fixture.revision + 1)
    }

    @Test
    fun `LOCATION lease delivers committed ordered locations and reconnect snapshot with stable IDs`() {
        val fixture = fixture()
        val owner = connect(fixture.owner)
        val guest = connect(fixture.guest)
        subscribe(owner, fixture)
        subscribe(guest, fixture)
        owner.send(command(fixture, "lease/acquire"), mapOf("target" to "LOCATION"))
        val grant = owner.json { it.path("type").asText() == "LEASE_GRANTED" }
        val token = grant.path("data").path("token").asText()
        val publicLease = guest.json { it.path("type").asText() == "LEASES" }
        assertThat(publicLease.toString()).doesNotContain("token", "sessionId", "connectionId")
        assertThat(publicLease.path("data").path("states").single().path("target").asText()).isEqualTo("LOCATION")
        guest.send(command(fixture, "lease/acquire"), mapOf("target" to "LOCATION"))
        assertThat(guest.json { it.path("type").asText() == "COMMAND_REJECTED" }
            .path("data").path("code").asText()).isEqualTo("BUNDLE_004")
        val requestId = UUID.randomUUID().toString()
        val transaction = TransactionTemplate(transactionManager)
        val saved = transaction.execute {
            val value = locations.saveDraft(actor(fixture.owner), fixture.bunchId, fixture.bundleId,
                BundleLocationsRequest(listOf(
                    BundleLocationInput("kakao", "a", "애월", "제주", 0),
                    BundleLocationInput("kakao", "b", "한담", "제주", 1)
                ), 0, UUID.fromString(token)), requestId)
            guest.assertNoEvent("LOCATION")
            value
        }!!
        val event = guest.json { it.path("type").asText() == "LOCATION" }
        assertThat(event.path("revision").asLong()).isEqualTo(saved.revision)
        assertThat(event.path("requestId").asText()).isEqualTo(requestId)
        assertThat(event.path("data").path("version").asLong()).isEqualTo(1)
        assertThat(event.path("data").path("locations").map { it.path("externalId").asText() })
            .containsExactly("a", "b")
        transaction.executeWithoutResult { tx ->
            locations.saveDraft(actor(fixture.owner), fixture.bunchId, fixture.bundleId,
                BundleLocationsRequest(emptyList(), 1, UUID.fromString(token)), "rollback-location")
            tx.setRollbackOnly()
        }
        guest.assertNoEvent("LOCATION")
        guest.close()
        val reconnected = connect(fixture.guest)
        subscribe(reconnected, fixture)
        val snapshot = getSnapshot(fixture, fixture.guest)
        assertThat(snapshot.path("locations").map { it.path("id").asLong() })
            .containsExactlyElementsOf(saved.locations.map { it.id })
        assertThat(snapshot.path("locationVersion").asLong()).isEqualTo(1)
        assertThat(snapshot.path("revision").asLong()).isEqualTo(saved.revision)
    }

    @Test
    fun `presence sends actual authenticated title and comment previews and restores fresh REST state`() {
        val fixture = fixture()
        val owner = connect(fixture.owner)
        val guest = connect(fixture.guest)
        subscribe(owner, fixture)
        subscribe(guest, fixture)
        val before = getSnapshot(fixture, fixture.owner)
        val activityBefore = jdbc.queryForList(
            "SELECT member_id, last_activity_at FROM bunch_memberships WHERE bunch_id = ? ORDER BY member_id",
            fixture.bunchId
        )
        owner.send(command(fixture, "presence"), mapOf("action" to "VIEW"))
        val ack = owner.presenceAck()
        assertThat(ack.path("data").path("members").size()).isEqualTo(1)
        assertThat(ack.path("revision").isNull).isTrue()
        assertThat(ack.toString()).doesNotContain("sessionId", "connectionId", "token")
        guest.json { it.path("type").asText() == "PRESENCE" }
        guest.send(command(fixture, "presence"), mapOf(
            "action" to "TYPE", "target" to "COMMENT", "text" to "실제 입력 👨‍👩‍👧‍👦",
            "memberId" to fixture.owner.memberId
        ))
        val preview = owner.json {
            it.path("type").asText() == "PRESENCE" && it.path("data").path("typing").size() == 1
        }
        assertThat(preview.path("data").path("typing").single().path("memberId").asLong())
            .isEqualTo(fixture.guest.memberId)
        assertThat(preview.path("data").path("typing").single().path("text").asText())
            .isEqualTo("실제 입력 👨‍👩‍👧‍👦")
        owner.send(command(fixture, "presence"), mapOf(
            "action" to "TYPE", "target" to "TITLE", "text" to "a".repeat(41)
        ))
        owner.json { it.path("type").asText() == "PRESENCE" && it.path("data").path("typing").size() == 2 }
        assertThat(getPresence(fixture, fixture.owner).path("typing").size()).isEqualTo(2)
        val after = getSnapshot(fixture, fixture.owner)
        assertThat(after.path("revision")).isEqualTo(before.path("revision"))
        assertThat(after.path("updatedAt")).isEqualTo(before.path("updatedAt"))
        assertThat(after.path("title")).isEqualTo(before.path("title"))
        assertThat(jdbc.queryForList(
            "SELECT member_id, last_activity_at FROM bunch_memberships WHERE bunch_id = ? ORDER BY member_id",
            fixture.bunchId
        )).isEqualTo(activityBefore)

        guest.send(command(fixture, "presence"), mapOf("action" to "BLUR"))
        owner.json {
            it.path("type").asText() == "PRESENCE" && it.path("data").path("typing").size() == 1
        }
        assertThat(getPresence(fixture, fixture.owner).path("members").size()).isEqualTo(2)
        guest.send(command(fixture, "presence"), mapOf("action" to "BACKGROUND"))
        owner.json {
            it.path("type").asText() == "PRESENCE" && it.path("data").path("members").size() == 1
        }
        val unauthorized = http.send(HttpRequest.newBuilder(URI(
            "http://localhost:$port/api/v1/bunches/${fixture.bunchId}/bundles/${fixture.bundleId}/presence"
        )).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(unauthorized.statusCode()).isEqualTo(401)
    }

    @Test
    fun `field editing presence is nonexclusive target scoped and leaves content and activity unchanged`() {
        val fixture = fixture()
        val owner = connect(fixture.owner)
        val guest = connect(fixture.guest)
        subscribe(owner, fixture)
        subscribe(guest, fixture)
        val before = getSnapshot(fixture, fixture.owner)
        val activityBefore = jdbc.queryForList(
            "SELECT member_id, last_activity_at FROM bunch_memberships WHERE bunch_id = ? ORDER BY member_id",
            fixture.bunchId
        )
        val startedAt = Instant.now()
        owner.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to "DATE"))
        assertThat(editingTargets(owner.presenceAck().path("data")))
            .containsExactly(fixture.owner.memberId to "DATE")
        guest.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to "LOCATION"))
        assertThat(editingTargets(guest.presenceAck().path("data"))).containsExactlyInAnyOrder(
            fixture.owner.memberId to "DATE", fixture.guest.memberId to "LOCATION"
        )
        val editing = owner.json {
            it.path("type").asText() == "PRESENCE" && it.path("data").path("editing").size() == 2
        }
        assertThat(editing.path("revision").isNull).isTrue()
        assertThat(editing.path("requestId").isNull).isTrue()
        assertThat(editingTargets(editing.path("data"))).containsExactlyInAnyOrder(
            fixture.owner.memberId to "DATE", fixture.guest.memberId to "LOCATION"
        )
        editing.path("data").path("editing").forEach { state ->
            assertThat(state.fieldNames().asSequence().toSet())
                .containsExactlyInAnyOrder("memberId", "target", "expiresAt")
            assertThat(Instant.parse(state.path("expiresAt").asText()))
                .isBetween(startedAt.plusSeconds(15), Instant.now().plusSeconds(15))
        }
        assertThat(getPresence(fixture, fixture.owner).path("editing")).isEqualTo(editing.path("data").path("editing"))
        assertThat(getPresence(fixture, fixture.guest).path("typing")).isEmpty()

        guest.clear()
        guest.send(command(fixture, "presence"), mapOf("action" to "END", "target" to "DATE"))
        assertThat(editingTargets(guest.presenceAck().path("data"))).containsExactlyInAnyOrder(
            fixture.owner.memberId to "DATE", fixture.guest.memberId to "LOCATION"
        )
        owner.clear()
        owner.send(command(fixture, "presence"), mapOf("action" to "END", "target" to "DATE"))
        assertThat(editingTargets(owner.presenceAck().path("data")))
            .containsExactly(fixture.guest.memberId to "LOCATION")

        val photoTarget = "PHOTO:${before.path("photos").single().path("id").asLong()}"
        for ((target, endAction) in listOf(
            photoTarget to "END", "TITLE" to "BLUR", "COMMENT" to "END", "LOCATION" to "END",
            "PHOTOS" to "END", "VISIBILITY" to "END", "PARTICIPANTS" to "END",
            photoTarget.replace("PHOTO:", "PICK:") to "END"
        )) {
            owner.clear()
            owner.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to target))
            val focused = owner.presenceAck().path("data")
            assertThat(editingTargets(focused)).containsExactlyInAnyOrder(
                fixture.owner.memberId to target, fixture.guest.memberId to "LOCATION"
            )
            assertThat(focused.path("typing")).isEmpty()
            owner.send(command(fixture, "presence"), mapOf("action" to endAction, "target" to target))
            assertThat(editingTargets(owner.presenceAck().path("data")))
                .containsExactly(fixture.guest.memberId to "LOCATION")
        }
        owner.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to "TITLE"))
        owner.presenceAck()
        owner.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to "COMMENT"))
        assertThat(editingTargets(owner.presenceAck().path("data"))).containsExactlyInAnyOrder(
            fixture.owner.memberId to "COMMENT", fixture.guest.memberId to "LOCATION"
        )
        owner.send(command(fixture, "presence"), mapOf("action" to "END"))
        assertThat(editingTargets(owner.presenceAck().path("data")))
            .containsExactly(fixture.guest.memberId to "LOCATION")
        owner.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to "TITLE"))
        owner.presenceAck()
        owner.send(command(fixture, "presence"), mapOf(
            "action" to "TYPE", "target" to "TITLE", "text" to "focus and preview are separate"
        ))
        assertThat(owner.presenceAck().path("data").path("typing").size()).isEqualTo(1)
        owner.send(command(fixture, "presence"), mapOf("action" to "STOP"))
        val stopped = owner.presenceAck().path("data")
        assertThat(stopped.path("typing")).isEmpty()
        assertThat(editingTargets(stopped)).containsExactly(fixture.guest.memberId to "LOCATION")
        guest.clear()
        guest.send(command(fixture, "presence"), mapOf("action" to "BACKGROUND"))
        assertThat(guest.presenceAck().path("data").path("editing")).isEmpty()
        assertThat(getPresence(fixture, fixture.owner).path("editing")).isEmpty()
        val after = getSnapshot(fixture, fixture.owner)
        assertThat(after.path("revision")).isEqualTo(before.path("revision"))
        assertThat(after.path("updatedAt")).isEqualTo(before.path("updatedAt"))
        assertThat(jdbc.queryForList(
            "SELECT member_id, last_activity_at FROM bunch_memberships WHERE bunch_id = ? ORDER BY member_id",
            fixture.bunchId
        )).isEqualTo(activityBefore)
    }

    @Test
    fun `field editing presence rejects foreign and unknown bundle photo targets without replacing valid state`() {
        val fixture = fixture()
        val foreign = fixture()
        val foreignPhoto = getSnapshot(foreign, foreign.owner).path("photos").single().path("id").asLong()
        val owner = connect(fixture.owner)
        subscribe(owner, fixture)
        owner.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to "DATE"))
        owner.presenceAck()
        for (target in listOf(
            "PHOTO:$foreignPhoto", "PHOTO:${Long.MAX_VALUE}", "PICK:$foreignPhoto", "PICK:${Long.MAX_VALUE}"
        )) {
            owner.clear()
            owner.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to target))
            val rejection = owner.json { it.path("type").asText() == "COMMAND_REJECTED" }
            assertThat(rejection.path("data").path("code").asText()).isNotBlank()
            assertThat(editingTargets(getPresence(fixture, fixture.owner)))
                .containsExactly(fixture.owner.memberId to "DATE")
        }
        val photoId = getSnapshot(fixture, fixture.owner).path("photos").single().path("id").asLong()
        val target = "PHOTO:$photoId"
        owner.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to target))
        assertThat(editingTargets(owner.presenceAck().path("data")))
            .containsExactly(fixture.owner.memberId to target)
        jdbc.update("DELETE FROM bundle_photos WHERE id = ?", photoId)
        owner.send(command(fixture, "presence"), mapOf("action" to "END", "target" to target))
        assertThat(owner.presenceAck().path("data").path("editing")).isEmpty()
    }

    @Test
    fun `presence permits view but rejects nonparticipant input and revoked REST access`() {
        val fixture = fixture()
        val viewerCredential = credential(member())
        join(viewerCredential.memberId, issue(fixture.owner.memberId, fixture.bunchId))
        val viewer = connect(viewerCredential)
        val owner = connect(fixture.owner)
        subscribe(owner, fixture)
        subscribe(viewer, fixture)
        viewer.send(command(fixture, "presence"), mapOf("action" to "VIEW"))
        viewer.presenceDeliveries()
        viewer.send(command(fixture, "presence"), mapOf(
            "action" to "TYPE", "target" to "COMMENT", "text" to "not my participant field"
        ))
        viewer.assertNoEvent("PRESENCE")
        assertThat(getPresence(fixture, fixture.owner).path("typing")).isEmpty()
        viewer.clear()
        owner.clear()
        viewer.send(command(fixture, "presence"), mapOf("action" to " begin ", "target" to "DATE"))
        assertThat(viewer.frames.poll(500, TimeUnit.MILLISECONDS)).isNull()
        owner.assertNoEvent("PRESENCE")
        assertThat(getPresence(fixture, fixture.owner).path("editing")).isEmpty()
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ? AND member_id = ?",
            fixture.bunchId, viewerCredential.memberId
        )
        val response = http.send(HttpRequest.newBuilder(URI(
            "http://localhost:$port/api/v1/bunches/${fixture.bunchId}/bundles/${fixture.bundleId}/presence"
        )).header("Authorization", "Bearer ${viewerCredential.access}").GET().build(),
            HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isBetween(400, 499)
    }

    @Test
    fun `presence cleanup follows commit and ignores rolled back bundle events`() {
        val fixture = fixture()
        val owner = connect(fixture.owner)
        subscribe(owner, fixture)
        owner.send(command(fixture, "presence"), mapOf("action" to "VIEW"))
        owner.json { it.path("type").asText() == "PRESENCE" }
        val transaction = TransactionTemplate(transactionManager)
        transaction.executeWithoutResult { tx ->
            events.publishEvent(BundleChangedEvent(
                fixture.bunchId, fixture.bundleId, fixture.revision, "rollback-presence", BundleEventType.DELETED, null
            ))
            assertThat(presence.get(fixture.owner.memberId, fixture.bunchId, fixture.bundleId).members).hasSize(1)
            tx.setRollbackOnly()
        }
        assertThat(getPresence(fixture, fixture.owner).path("members").size()).isEqualTo(1)
        transaction.executeWithoutResult {
            events.publishEvent(BundleChangedEvent(
                fixture.bunchId, fixture.bundleId, fixture.revision, "commit-presence", BundleEventType.PUBLISHED, null
            ))
            assertThat(presence.get(fixture.owner.memberId, fixture.bunchId, fixture.bundleId).members).hasSize(1)
        }
        assertThat(getPresence(fixture, fixture.owner).path("members")).isEmpty()
    }

    @Test
    fun `committed participation removal and deletion clear presence while rolled back removal preserves it`() {
        val fixture = fixture()
        val owner = connect(fixture.owner)
        val guest = connect(fixture.guest)
        val secondGuest = connect(fixture.guest)
        subscribe(owner, fixture)
        subscribe(guest, fixture)
        subscribe(secondGuest, fixture)
        guest.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to "DATE"))
        assertThat(editingTargets(guest.presenceAck().path("data")))
            .containsExactly(fixture.guest.memberId to "DATE")
        secondGuest.send(command(fixture, "presence"), mapOf(
            "action" to "TYPE", "target" to "COMMENT", "text" to "both connections leave after commit"
        ))
        secondGuest.json { it.path("type").asText() == "PRESENCE" && it.path("data").path("typing").size() == 1 }
        val transaction = TransactionTemplate(transactionManager)
        transaction.executeWithoutResult { tx ->
            bundles.removeParticipant(actor(fixture.owner), fixture.bunchId, fixture.bundleId,
                fixture.guest.memberId, "rollback-removal")
            assertThat(presence.get(fixture.owner.memberId, fixture.bunchId, fixture.bundleId).members).hasSize(1)
            tx.setRollbackOnly()
        }
        assertThat(getPresence(fixture, fixture.owner).path("members").size()).isEqualTo(1)
        assertThat(editingTargets(getPresence(fixture, fixture.owner)))
            .contains(fixture.guest.memberId to "DATE")
        bundles.removeParticipant(actor(fixture.owner), fixture.bunchId, fixture.bundleId,
            fixture.guest.memberId, "commit-removal")
        assertThat(getPresence(fixture, fixture.owner).path("members")).isEmpty()
        assertThat(getPresence(fixture, fixture.owner).path("editing")).isEmpty()
        secondGuest.clear()
        secondGuest.send(command(fixture, "presence"), mapOf(
            "action" to "TYPE", "target" to "COMMENT", "text" to "removed input"
        ))
        secondGuest.assertNoEvent("PRESENCE")
        owner.send(command(fixture, "presence"), mapOf("action" to "VIEW"))
        owner.json { it.path("type").asText() == "PRESENCE" && it.path("data").path("members").size() == 1 }
        bundles.delete(actor(fixture.owner), fixture.bunchId, fixture.bundleId, "delete-presence")
        // The bundle no longer exists, so REST correctly rejects reads. The cleared
        // in-memory state is also verified by the unit cleanup test.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundles WHERE id = ?", Long::class.java,
            fixture.bundleId)).isZero()
    }

    @Test
    fun `actual publish clears preview and refuses subsequent presence commands`() {
        val fixture = fixture()
        bundles.title(actor(fixture.owner), fixture.bunchId, fixture.bundleId,
            BundleTextRequest("발행할 제목", 0, BundleStatus.DRAFT), "presence-title")
        listOf(fixture.owner, fixture.guest).forEach { author ->
            bundles.comment(actor(author), fixture.bunchId, fixture.bundleId,
                BundleTextRequest("발행 코멘트", 0, BundleStatus.DRAFT), "presence-comment-${author.memberId}")
        }
        val owner = connect(fixture.owner)
        val guest = connect(fixture.guest)
        subscribe(owner, fixture)
        subscribe(guest, fixture)
        guest.send(command(fixture, "presence"), mapOf(
            "action" to "TYPE", "target" to "COMMENT", "text" to "미저장 preview"
        ))
        guest.json { it.path("type").asText() == "PRESENCE" && it.path("data").path("typing").size() == 1 }
        owner.clear()
        owner.send(command(fixture, "presence"), mapOf("action" to "BEGIN", "target" to "DATE"))
        assertThat(editingTargets(owner.presenceAck().path("data")))
            .contains(fixture.owner.memberId to "DATE")
        bundles.publish(actor(fixture.owner), fixture.bunchId, fixture.bundleId, UUID.randomUUID(),
            PublishBundleRequest(visibility = Visibility.PRIVATE))
        assertThat(getPresence(fixture, fixture.owner).path("members")).isEmpty()
        assertThat(getPresence(fixture, fixture.owner).path("typing")).isEmpty()
        assertThat(getPresence(fixture, fixture.owner).path("editing")).isEmpty()
        owner.clear()
        owner.send(command(fixture, "presence"), mapOf("action" to "VIEW"))
        val rejection = owner.json { it.path("type").asText() == "COMMAND_REJECTED" }
        assertThat(rejection.path("data").path("code").asText())
            .isEqualTo(ErrorCode.BUNDLE_STATE_CONFLICT.code)
        assertThat(getPresence(fixture, fixture.owner).path("members")).isEmpty()
    }

    private fun editingTargets(data: JsonNode): List<Pair<Long, String>> {
        assertThat(data.path("editing").isArray).isTrue()
        return data.path("editing").map { it.path("memberId").asLong() to it.path("target").asText() }
    }

    private fun getPresence(fixture: Fixture, credential: Credential): JsonNode {
        val response = http.send(HttpRequest.newBuilder(URI(
            "http://localhost:$port/api/v1/bunches/${fixture.bunchId}/bundles/${fixture.bundleId}/presence"
        )).header("Authorization", "Bearer ${credential.access}").GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        return mapper.readTree(response.body()).path("data")
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

    private fun actor(value: Credential) = BundleActor(value.memberId, value.sid)
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
        client.send(command(fixture, "activity"), mapOf("type" to "VIEW"))
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
            .header("Content-Type", "application/json")
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
        fun presenceDeliveries() {
            val subscriptions = mutableSetOf<String>()
            while (subscriptions.size < 2) {
                val delivery = frame {
                    it.command == "MESSAGE" && mapper.readTree(it.body).path("type").asText() == "PRESENCE"
                }
                subscriptions.add(delivery.headers.getValue("subscription"))
            }
            assertThat(subscriptions).containsExactlyInAnyOrder("topic", "activity")
        }

        fun presenceAck(): JsonNode = mapper.readTree(frame {
            it.command == "MESSAGE" && it.headers["subscription"] == "activity" &&
                mapper.readTree(it.body).path("type").asText() == "PRESENCE"
        }.body)

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
