package com.bunch.bundle

import com.bunch.bundle.domain.BundleActivityType
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.dto.*
import com.bunch.bundle.controller.BundleSocketErrorHandler
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BundleRealtimeMessageTest {
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val expiresAt = Instant.parse("2026-10-25T10:00:15Z")

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
    fun `lease states and activity have explicit distinct event enums without credentials`() {
        val states = BundleLeaseStateChangedEvent(1, 2, 7, listOf(LeaseState("TITLE", 5, expiresAt, 4)))
        val stateJson = envelope(states.toMessage(), BundleEventType.LEASES)
        assertThat(stateJson.path("data").path("states").single().path("memberId").asLong()).isEqualTo(5)
        assertThat(stateJson.toString()).doesNotContain("token", "sessionId", "screenId")

        val activity = BundleActivityEvent(1, 2, 5, BundleActivityType.COMMENT, 7)
        val activityJson = envelope(activity.toMessage(), BundleEventType.ACTIVITY)
        assertThat(activityJson.path("data").path("activity").asText()).isEqualTo("COMMENT")
        assertThat(activityJson.path("data").path("memberId").asLong()).isEqualTo(5)
        assertThat(activityJson.path("requestId").isNull).isTrue()
    }

    @Test
    fun `private grants heartbeats releases and rejections share same envelope`() {
        val token = UUID.randomUUID()
        val grant = LeaseGrant(1, 2, "TITLE", token, 4, expiresAt, 7)
        listOf(BundleEventType.LEASE_GRANTED, BundleEventType.LEASE_HEARTBEAT).forEach { type ->
            val json = envelope(grant.toMessage(type), type)
            assertThat(json.path("data").path("token").asText()).isEqualTo(token.toString())
            assertThat(json.path("data").path("leaseVersion").asLong()).isEqualTo(4)
        }
        val released = envelope(BundleLeaseReleaseAck(1, 2, "TITLE", 7).toMessage(), BundleEventType.LEASE_RELEASED)
        assertThat(released.path("data").path("target").asText()).isEqualTo("TITLE")

        val rejected = envelope(BundleRealtimeMessage(
            type = BundleEventType.COMMAND_REJECTED,
            bunchId = 1,
            bundleId = 2,
            data = BundleCommandRejectedData("BUNDLE_004")
        ), BundleEventType.COMMAND_REJECTED)
        assertThat(rejected.path("revision").isNull).isTrue()
        assertThat(rejected.path("data").path("code").asText()).isEqualTo("BUNDLE_004")
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
