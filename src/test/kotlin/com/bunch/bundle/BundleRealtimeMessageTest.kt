package com.bunch.bundle

import com.bunch.bundle.domain.BundleActivityType
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.dto.*
import com.bunch.bundle.controller.BundleSocketErrorHandler
import com.bunch.bundle.service.BundleRealtimeDestinations
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class BundleRealtimeMessageTest {
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val expiresAt = Instant.parse("2026-10-25T10:00:15Z")

    @Test
    fun `legacy editing lease commands are no longer accepted`() {
        listOf("acquire", "heartbeat", "release").forEach { action ->
            assertThat(BundleRealtimeDestinations.parseCommand("/app/bunches/1/bundles/2/lease/$action"))
                .describedAs("removed lease command %s", action)
                .isNull()
        }
        assertThat(BundleRealtimeDestinations.parseCommand("/app/bunches/1/bundles/2/presence")).isNotNull()
        assertThat(BundleRealtimeDestinations.parseCommand("/app/bunches/1/bundles/2/activity")).isNotNull()
    }

    @Test
    fun `only the existing activity queue remains a private subscription`() {
        assertThat(BundleRealtimeDestinations.isPrivateSubscription("/user/queue/bundle-leases")).isFalse()
        assertThat(BundleRealtimeDestinations.isPrivateSubscription("/user/queue/bundle-activity")).isTrue()
    }

    @Test
    fun `content events preserve stored values version and request correlation in common envelope`() {
        val message = BundleChangedEvent(
            bunchId = 1,
            bundleId = 2,
            revision = 7,
            requestId = "request-7",
            type = BundleEventType.TITLE,
            changes = mapOf("value" to "여행", "version" to 3)
        ).toMessage()

        val json = envelope(message, BundleEventType.TITLE)
        assertThat(json.path("requestId").asText()).isEqualTo("request-7")
        assertThat(json.path("revision").asLong()).isEqualTo(7)
        assertThat(json.path("data").path("value").asText()).isEqualTo("여행")
        assertThat(json.path("data").path("version").asLong()).isEqualTo(3)
    }

    @Test
    fun `activity uses the common envelope without credentials`() {
        val activity = BundleActivityEvent(1, 2, 5, BundleActivityType.COMMENT, 7)
        val json = envelope(activity.toMessage(), BundleEventType.ACTIVITY)
        assertThat(json.path("data").path("activity").asText()).isEqualTo("COMMENT")
        assertThat(json.path("data").path("memberId").asLong()).isEqualTo(5)
        assertThat(json.path("requestId").isNull).isTrue()
        assertThat(json.toString()).doesNotContain("token", "sessionId", "connectionId")
    }

    @Test
    fun `command rejections use the common envelope without content revision`() {
        val rejected = envelope(BundleRealtimeMessage(
            type = BundleEventType.COMMAND_REJECTED,
            bunchId = 1,
            bundleId = 2,
            data = BundleCommandRejectedData(ErrorCode.INVALID_INPUT.code)
        ), BundleEventType.COMMAND_REJECTED)
        assertThat(rejected.path("revision").isNull).isTrue()
        assertThat(rejected.path("data").path("code").asText()).isEqualTo(ErrorCode.INVALID_INPUT.code)
    }

    @Test
    fun `deleted events keep explicit null data and content revision`() {
        val json = envelope(
            BundleChangedEvent(1, 2, 8, "delete-8", BundleEventType.DELETED, null).toMessage(),
            BundleEventType.DELETED
        )
        assertThat(json.path("data").isNull).isTrue()
        assertThat(json.path("revision").asLong()).isEqualTo(8)
    }

    @Test
    fun `protocol errors also carry the common envelope without untrusted scope or credentials`() {
        val error = BundleSocketErrorHandler().handleClientMessageProcessingError(
            null, BusinessException(ErrorCode.UNAUTHORIZED)
        )
        val json = mapper.readTree(error.payload)
        assertThat(json.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "type", "bunchId", "bundleId", "revision", "requestId", "data"
        )
        assertThat(json.path("type").asText()).isEqualTo("COMMAND_REJECTED")
        assertThat(json.path("bunchId").isNull).isTrue()
        assertThat(json.path("bundleId").isNull).isTrue()
        assertThat(json.path("data").path("code").asText()).isEqualTo("AUTH_001")
        assertThat(json.toString()).doesNotContain("Authorization", "token", "sessionId")
    }

    @Test
    fun `presence uses existing envelope without content revision or credentials`() {
        val data = BundlePresenceData(
            listOf(BundlePresenceMember(5, expiresAt)),
            listOf(BundleTypingState(5, "COMMENT", "actual input", expiresAt))
        )
        val json = envelope(data.toMessage(1, 2), BundleEventType.PRESENCE)
        assertThat(json.path("revision").isNull).isTrue()
        assertThat(json.path("requestId").isNull).isTrue()
        assertThat(json.path("data").path("typing").single().path("text").asText()).isEqualTo("actual input")
        assertThat(json.toString()).doesNotContain("sessionId", "connectionId", "token")
    }

    private fun envelope(value: Any, type: BundleEventType): JsonNode {
        val json = mapper.readTree(mapper.writeValueAsBytes(value))
        assertThat(json.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "type", "bunchId", "bundleId", "revision", "requestId", "data"
        )
        assertThat(json.path("type").asText()).isEqualTo(type.name)
        assertThat(json.path("bunchId").asLong()).isEqualTo(1)
        assertThat(json.path("bundleId").asLong()).isEqualTo(2)
        return json
    }
}
