package com.bunch.notification

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.Visibility
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.PushAuthorizationStatus
import com.bunch.notification.domain.PushDevice
import com.bunch.notification.domain.PushDeviceEnvironment
import com.bunch.notification.domain.PushDeviceRepository
import com.bunch.notification.domain.PushJob
import com.bunch.notification.domain.PushJobRepository
import com.bunch.notification.domain.PushPreferenceRepository
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.service.DeviceTokenCipher
import com.bunch.notification.service.PushJobWriter
import com.bunch.notification.service.PushPayloadFactory
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
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
        assertThat(encrypted).doesNotContain(*token.toByteArray())
        assertThat(cipher.hash(token)).hasSize(64)
    }

    @Test
    fun `writer snapshots only authorized current devices and keeps job payload contract`() {
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
            authorizationStatus = PushAuthorizationStatus.AUTHORIZED,
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
        verify(jobs).save(saved.capture())
        assertThat(saved.value.recipientMemberId).isEqualTo(1)
        assertThat(saved.value.deviceId).isEqualTo(0)
        assertThat(saved.value.notificationId).isEqualTo(42)
        assertThat(saved.value.payload.type).isEqualTo(NotificationType.DRAFT_ACTIVITY)
        assertThat(saved.value.payload.aps.alert.title).isEqualTo("[번치] 새 활동")
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
