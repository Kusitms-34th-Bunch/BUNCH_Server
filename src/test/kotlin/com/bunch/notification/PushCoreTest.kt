package com.bunch.notification

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.Visibility
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.push.PushAuthorizationStatus
import com.bunch.notification.domain.push.PushDevice
import com.bunch.notification.domain.push.PushDeviceEnvironment
import com.bunch.notification.domain.push.PushDeviceRepository
import com.bunch.notification.domain.push.PushJob
import com.bunch.notification.domain.push.PushJobRepository
import com.bunch.notification.domain.push.PushPreferenceRepository
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.dto.PushPayload
import com.bunch.notification.service.DeviceTokenCipher
import com.bunch.notification.service.PushJobWriter
import com.bunch.notification.service.PushPayloadFactory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.UUID

class PushCoreTest {
    @Test
    fun `device token is encrypted and can be recovered without storing plaintext`() {
        val cipher = DeviceTokenCipher(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        val token = "aabbccdd"

        val encrypted = cipher.encrypt(token)

        assertThat(cipher.decrypt(encrypted)).isEqualTo(token)
        assertThat(encrypted).isNotEqualTo(token.toByteArray())
        assertThat(cipher.encrypt(token)).isNotEqualTo(encrypted)
        val tampered = encrypted.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        assertThatThrownBy { cipher.decrypt(tampered) }
            .isInstanceOf(javax.crypto.AEADBadTagException::class.java)
        assertThat(cipher.hash(token)).hasSize(64)
    }

    @ParameterizedTest
    @CsvSource(
        "AUTHORIZED,true",
        "PROVISIONAL,true",
        "DENIED,false",
        "NOT_DETERMINED,false"
    )
    fun `writer snapshots only authorized current devices and keeps job payload contract`(
        authorization: PushAuthorizationStatus,
        receivesPush: Boolean
    ) {
        val devices = mock(PushDeviceRepository::class.java)
        val preferences = mock(PushPreferenceRepository::class.java)
        val jobs = mock(PushJobRepository::class.java)
        val bunches = mock(BunchRepository::class.java)
        val mapper = ObjectMapper().findAndRegisterModules()
        val device = PushDevice(
            installationId = UUID.randomUUID(),
            memberId = 1,
            sessionId = UUID.randomUUID(),
            environment = PushDeviceEnvironment.SANDBOX,
            topic = "com.bunch.app",
            tokenCiphertext = byteArrayOf(1, 2, 3),
            tokenHash = "a".repeat(64),
            authorizationStatus = authorization,
            registeredAt = Instant.parse("2026-01-01T00:00:00Z")
        )
        val notification = Notification(
            id = 42,
            recipientMemberId = 1,
            bunchId = 7,
            type = NotificationType.DRAFT_ACTIVITY,
            dedupeKey = "DRAFT_ACTIVITY:7:1",
            title = "새 활동",
            body = "사진 1장을 추가했어요.",
            target = NotificationTarget(NotificationTargetKind.DRAFT, 7, 9),
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
        )
        `when`(bunches.findById(7)).thenReturn(
            Optional.of(
                Bunch(
                    name = "번치",
                    introduction = "소개",
                    visibility = Visibility.PUBLIC,
                    characterType = CharacterType.TYPE_1,
                    createdBy = 1,
                    creationRequestKey = UUID.randomUUID(),
                    creationFingerprint = "fingerprint",
                    createdAt = Instant.parse("2026-01-01T00:00:00Z")
                )
            )
        )
        val payloads = PushPayloadFactory(bunches, mapper)
        `when`(devices.findAllByMemberId(1)).thenReturn(listOf(device))
        `when`(preferences.findById(1)).thenReturn(Optional.empty())
        `when`(jobs.existsByRecipientMemberIdAndDeviceIdAndDedupeKey(1, 0, notification.dedupeKey))
            .thenReturn(false)
        val writer = PushJobWriter(devices, preferences, jobs, payloads)

        writer.enqueue(listOf(notification), notification.createdAt)

        val saved = ArgumentCaptor.forClass(PushJob::class.java)
        if (!receivesPush) {
            verify(jobs, never()).save(saved.capture())
            return
        }
        verify(jobs).save(saved.capture())
        assertThat(saved.value.recipientMemberId).isEqualTo(1)
        assertThat(saved.value.deviceId).isEqualTo(0)
        assertThat(saved.value.notificationId).isEqualTo(42)
        assertThat(saved.value.payload.notificationId).isEqualTo(42)
        assertThat(saved.value.payload.eventId).isEqualTo(saved.value.id)
        assertThat(saved.value.payload.target).containsEntry("parentCommentId", null)
        assertThat(saved.value.payload.type).isEqualTo(NotificationType.DRAFT_ACTIVITY)
        assertThat(saved.value.payload.aps.alert.title).isEqualTo("[번치] 새 활동")
    }

    @Test
    fun `detached payload has explicit null inbox id and old JSON still deserializes`() {
        val mapper = ObjectMapper().findAndRegisterModules()
        val factory = PushPayloadFactory(mock(BunchRepository::class.java), mapper)
        val id = UUID.randomUUID()
        val payload = factory.forDetached(
            id, NotificationType.BUNCH_DELETED, "삭제 안내", "번치가 삭제됐어요",
            NotificationTarget(NotificationTargetKind.ACCESS_NOTICE, 7, noticeId = 9)
        )
        val json = mapper.valueToTree<JsonNode>(payload)
        assertThat(json.has("notificationId")).isTrue()
        assertThat(json.path("notificationId").isNull).isTrue()
        assertThat(json.path("target").path("parentCommentId").isNull).isTrue()
        val legacy = json.deepCopy<ObjectNode>()
        legacy.remove("notificationId")
        (legacy.path("target") as ObjectNode).remove("parentCommentId")
        assertThat(mapper.treeToValue(legacy, PushPayload::class.java).notificationId).isNull()
        val target = mapper.readValue(
            """{"kind":"BUNDLE_COMMENTS","bunchId":7,"bundleId":9,"commentId":11}""",
            NotificationTarget::class.java
        )
        assertThat(target.parentCommentId).isNull()
    }

    @Test
    fun `delivery fitting preserves deep link fields and title while shortening oversized legacy body`() {
        val mapper = ObjectMapper().findAndRegisterModules()
        val factory = PushPayloadFactory(mock(BunchRepository::class.java), mapper)
        val original = factory.forDetached(
            UUID.randomUUID(), NotificationType.MEMBER_JOINED, "원래 제목", "본문",
            NotificationTarget(NotificationTargetKind.MEMBERS, 7)
        ).copy(notificationId = 42)
        val oversized = original.copy(
            aps = original.aps.copy(alert = original.aps.alert.copy(body = "가".repeat(1500)))
        )
        assertThat(mapper.writeValueAsBytes(oversized).size).isGreaterThan(4096)

        val fitted = factory.fitForDelivery(oversized)

        assertThat(mapper.writeValueAsBytes(fitted).size).isLessThanOrEqualTo(4096)
        assertThat(fitted.copy(aps = original.aps)).isEqualTo(original)
        assertThat(fitted.aps.alert.title).isEqualTo(original.aps.alert.title)
        assertThat(fitted.aps.alert.body).endsWith("…")
    }

    @Test
    fun `payload factory trims social comments and stays within APNs limit`() {
        val bunches = mock(BunchRepository::class.java)
        val mapper = ObjectMapper().findAndRegisterModules()
        val bunch = Bunch(
            name = "테스트 번치",
            introduction = "소개",
            visibility = Visibility.PUBLIC,
            characterType = CharacterType.TYPE_1,
            createdBy = 1,
            creationRequestKey = UUID.randomUUID(),
            creationFingerprint = "fingerprint",
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
        )
        `when`(bunches.findById(7)).thenReturn(Optional.of(bunch))
        val factory = PushPayloadFactory(bunches, mapper)
        val notification = Notification(
            id = 42,
            recipientMemberId = 1,
            bunchId = 7,
            type = NotificationType.SOCIAL_COMMENT_CREATED,
            dedupeKey = "comment:1",
            title = "댓글이 달렸어요",
            body = "가\n" + "👨‍👩‍👧‍👦".repeat(60),
            target = NotificationTarget(NotificationTargetKind.BUNDLE_COMMENTS, 7, 9),
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
        )

        val payload = factory.forNotification(UUID.randomUUID(), notification)

        assertThat(mapper.writeValueAsBytes(payload)).hasSizeLessThanOrEqualTo(4096)
        assertThat(payload.aps.alert.title).startsWith("[테스트 번치]")
        assertThat(payload.aps.alert.body).endsWith("…")
        assertThat(payload.aps.alert.body).startsWith("가 ").doesNotContain("\n")
        assertThat(java.util.regex.Pattern.compile("\\X").matcher(payload.aps.alert.body).results().count())
            .isEqualTo(51)
    }
}
