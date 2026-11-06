package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationHttpIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var participants: BundleParticipantRepository

    @MockitoBean
    private lateinit var clock: Clock

    private lateinit var now: Instant
    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @BeforeEach
    fun setNanosecondClock() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_995)
        `when`(clock.instant()).thenReturn(now)
    }

    @AfterEach
    fun cleanNotificationBundleReferencesBeforeBunchSupport() {
        jdbc.update("DELETE FROM bundle_edit_leases")
        jdbc.update("DELETE FROM bundle_comments")
        jdbc.update("DELETE FROM bundle_participants")
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundle_requests")
        jdbc.update("DELETE FROM bundles")
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    @Test
    fun `notification routes expose common response and wire shapes`() {
        val fixture = producerFixture()

        val listed = listRequest(fixture.owner, fixture.bunchId)
            .expectSuccessEnvelope()
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.unreadCount").value(1))
            .andReturn()
        val listedData = listed.data()
        assertThat(fieldNames(listedData)).containsExactlyInAnyOrder(
            "items", "page", "size", "totalElements", "hasNext", "unreadCount"
        )
        val item = listedData.path("items").single()
        assertThat(fieldNames(item)).containsExactlyInAnyOrder(
            "id", "type", "title", "body", "createdAt", "readAt", "target"
        )
        assertThat(item.path("type").asText()).isEqualTo(NotificationType.MEMBER_JOINED.name)
        assertThat(item.path("title").asText()).isEqualTo("새 멤버가 들어왔어요")
        assertThat(item.path("body").asText()).isEqualTo("${fixture.joinedNickname}님이 번치에 참여했어요.")
        assertThat(item.path("readAt").isNull).isTrue()
        assertThat(fieldNames(item.path("target"))).containsExactlyInAnyOrder(
            "kind", "bunchId", "bundleId", "noticeId", "targetBunchId", "sourceBunchId"
        )
        assertThat(item.path("target").path("kind").asText()).isEqualTo(NotificationTargetKind.MEMBERS.name)
        assertThat(item.path("target").path("bunchId").asLong()).isEqualTo(fixture.bunchId)
        assertThat(item.path("target").path("bundleId").isNull).isTrue()
        assertThat(item.path("target").path("noticeId").isNull).isTrue()
        assertThat(item.path("target").path("targetBunchId").isNull).isTrue()

        val notificationId = item.path("id").asLong()
        val firstRead = readRequest(fixture.owner, fixture.bunchId, notificationId)
            .expectSuccessEnvelope()
            .andReturn()
        val firstReadData = firstRead.data()
        assertThat(fieldNames(firstReadData)).containsExactlyInAnyOrder("id", "readAt")
        assertThat(firstReadData.path("id").asLong()).isEqualTo(notificationId)
        val readAt = firstReadData.path("readAt").asText()
        assertThat(readAt).isEqualTo(now.truncatedTo(ChronoUnit.MICROS).toString())

        val repeatedReadData = readRequest(fixture.owner, fixture.bunchId, notificationId)
            .expectSuccessEnvelope()
            .andReturn()
            .data()
        assertThat(fieldNames(repeatedReadData)).containsExactlyInAnyOrder("id", "readAt")
        assertThat(repeatedReadData.path("readAt").asText()).isEqualTo(readAt)

        val opened = openRequest(fixture.owner, fixture.bunchId, notificationId)
            .expectSuccessEnvelope()
            .andReturn()
        val openedData = opened.data()
        assertThat(fieldNames(openedData)).containsExactlyInAnyOrder(
            "notificationId", "readAt", "available", "target"
        )
        assertThat(openedData.path("notificationId").asLong()).isEqualTo(notificationId)
        assertThat(openedData.path("readAt").asText()).isEqualTo(readAt)
        assertThat(openedData.path("available").asBoolean()).isTrue()
        assertThat(openedData.path("target").path("kind").asText()).isEqualTo(NotificationTargetKind.MEMBERS.name)
        assertThat(openedData.path("target").path("bunchId").asLong()).isEqualTo(fixture.bunchId)

        val readAllData = readAllRequest(fixture.owner, fixture.bunchId)
            .expectSuccessEnvelope()
            .andReturn()
            .data()
        assertThat(fieldNames(readAllData)).containsExactlyInAnyOrder("unreadCount")
        assertThat(readAllData.path("unreadCount").asLong()).isZero()

        val emptyOwner = sessionFor(member())
        val emptyBunchId = create(emptyOwner.memberId).id
        val emptyReadAll = readAllRequest(emptyOwner, emptyBunchId)
            .expectSuccessEnvelope()
            .andReturn()
            .data()
        assertThat(fieldNames(emptyReadAll)).containsExactlyInAnyOrder("unreadCount")
        assertThat(emptyReadAll.path("unreadCount").asLong()).isZero()
    }

    @Test
    fun `valid JWT without an active Redis session is unauthorized`() {
        val auth = sessionFor(member())
        sessions.delete(auth.sessionId, auth.memberId)

        listRequest(auth, 1)
            .expectError(ErrorCode.UNAUTHORIZED)
    }

    @Test
    fun `notification list validates page and size boundaries`() {
        val owner = sessionFor(member())
        val bunchId = create(owner.memberId).id

        listRequest(owner, bunchId, size = "0").expectError(ErrorCode.INVALID_INPUT)
        listRequest(owner, bunchId, size = "51").expectError(ErrorCode.INVALID_INPUT)
        listRequest(owner, bunchId, page = "-1").expectError(ErrorCode.INVALID_INPUT)
        listRequest(owner, bunchId, page = "not-a-number").expectError(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun `foreign and missing notifications are indistinguishable while nonmembers are forbidden`() {
        val owner = sessionFor(member())
        val otherMember = sessionFor(member())
        val bunchId = create(owner.memberId).id
        join(otherMember.memberId, issue(owner.memberId, bunchId))
        val notificationId = notifications.findAll().single().id
        val missingId = notificationId + 100_000L

        val foreignRead = readRequest(otherMember, bunchId, notificationId)
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)
            .andReturn()
            .response
            .contentAsString
        val missingRead = readRequest(otherMember, bunchId, missingId)
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)
            .andReturn()
            .response
            .contentAsString
        assertThat(foreignRead).isEqualTo(missingRead)

        val foreignOpen = openRequest(otherMember, bunchId, notificationId)
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)
            .andReturn()
            .response
            .contentAsString
        val missingOpen = openRequest(otherMember, bunchId, missingId)
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)
            .andReturn()
            .response
            .contentAsString
        assertThat(foreignOpen).isEqualTo(missingOpen)

        val outsider = sessionFor(member())
        listRequest(outsider, bunchId).expectError(ErrorCode.FORBIDDEN)
        readAllRequest(outsider, bunchId).expectError(ErrorCode.FORBIDDEN)
    }

    @Test
    fun `opening disappeared draft and published targets reads notification but returns unavailable`() {
        val owner = sessionFor(member())
        val bunchId = create(owner.memberId).id

        val draft = bundles.saveAndFlush(Bundle(bunchId = bunchId, createdBy = owner.memberId))
        participants.saveAndFlush(BundleParticipant(bundleId = draft.id, memberId = owner.memberId))
        val draftNotification = saveNotification(
            owner.memberId,
            bunchId,
            NotificationType.DRAFT_PARTICIPANT_ADDED,
            NotificationTarget(NotificationTargetKind.DRAFT, bunchId, draft.id)
        )
        jdbc.update("DELETE FROM bundle_participants WHERE bundle_id = ?", draft.id)
        bundles.deleteById(draft.id)

        val published = bundles.saveAndFlush(
            Bundle(bunchId = bunchId, createdBy = owner.memberId, status = BundleStatus.PUBLISHED)
        )
        val publishedNotification = saveNotification(
            owner.memberId,
            bunchId,
            NotificationType.BUNDLE_PUBLISHED,
            NotificationTarget(NotificationTargetKind.BUNDLE, bunchId, published.id)
        )
        bundles.deleteById(published.id)

        listOf(draftNotification, publishedNotification).forEach { notification ->
            val opened = openRequest(owner, bunchId, notification.id)
                .expectSuccessEnvelope()
                .andReturn()
                .data()
            assertThat(fieldNames(opened)).containsExactlyInAnyOrder(
                "notificationId", "readAt", "available", "target"
            )
            assertThat(opened.path("notificationId").asLong()).isEqualTo(notification.id)
            assertThat(opened.path("readAt").asText())
                .isEqualTo(now.truncatedTo(ChronoUnit.MICROS).toString())
            assertThat(opened.path("available").asBoolean()).isFalse()
            assertThat(opened.path("target").isNull).isTrue()

            val listed = listRequest(owner, bunchId)
                .expectSuccessEnvelope()
                .andReturn()
                .data()
                .path("items")
                .first { it.path("id").asLong() == notification.id }
            assertThat(listed.path("readAt").asText()).isEqualTo(opened.path("readAt").asText())
        }
    }

    private fun producerFixture(): ProducerFixture {
        val owner = sessionFor(member())
        val joinedMemberId = member()
        val bunchId = create(owner.memberId).id
        join(joinedMemberId, issue(owner.memberId, bunchId))
        val joinedNickname = members.findById(joinedMemberId).orElseThrow().nickname!!
        return ProducerFixture(owner, bunchId, joinedNickname)
    }

    private fun saveNotification(
        recipientMemberId: Long,
        bunchId: Long,
        type: NotificationType,
        target: NotificationTarget
    ): Notification = notifications.saveAndFlush(
        Notification(
            recipientMemberId = recipientMemberId,
            bunchId = bunchId,
            type = type,
            dedupeKey = "http-test:${UUID.randomUUID()}",
            title = "테스트 알림",
            body = "테스트 본문",
            target = target,
            createdAt = Instant.now()
        )
    )

    private fun listRequest(
        auth: Auth,
        bunchId: Long,
        page: String? = null,
        size: String? = null
    ): ResultActions {
        val request = get(notificationsPath(bunchId))
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
        page?.let { request.param("page", it) }
        size?.let { request.param("size", it) }
        return mvc.perform(request)
    }

    private fun readRequest(auth: Auth, bunchId: Long, notificationId: Long): ResultActions = mvc.perform(
        put("${notificationsPath(bunchId)}/$notificationId/read")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
    )

    private fun readAllRequest(auth: Auth, bunchId: Long): ResultActions = mvc.perform(
        post("${notificationsPath(bunchId)}/read-all")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
    )

    private fun openRequest(auth: Auth, bunchId: Long, notificationId: Long): ResultActions = mvc.perform(
        post("${notificationsPath(bunchId)}/$notificationId/open")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
    )

    private fun sessionFor(memberId: Long): Auth {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(
            sessionId,
            memberId,
            pair.refreshToken,
            tokens.getRefreshTokenValiditySeconds()
        )
        sessionIds += sessionId to memberId
        return Auth(memberId, pair.accessToken, sessionId)
    }

    private fun bearer(auth: Auth): String = "Bearer ${auth.accessToken}"

    private fun notificationsPath(bunchId: Long): String =
        "/api/v1/bunches/$bunchId/notifications"

    private fun fieldNames(node: JsonNode): Set<String> =
        node.fieldNames().asSequence().toSet()

    private fun ResultActions.expectSuccessEnvelope(): ResultActions =
        andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").exists())
            .andExpect(jsonPath("$.error").isEmpty)

    private fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty)
            .andExpect(jsonPath("$.error.code").value(error.code))
            .andExpect(jsonPath("$.error.message").value(error.messageKey))

    private fun MvcResult.data(): JsonNode =
        mapper.readTree(response.contentAsString).path("data")

    private data class Auth(
        val memberId: Long,
        val accessToken: String,
        val sessionId: String
    )

    private data class ProducerFixture(
        val owner: Auth,
        val bunchId: Long,
        val joinedNickname: String
    )
}
