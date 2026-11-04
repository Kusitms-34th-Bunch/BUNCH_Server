package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.PushAuthorizationStatus
import com.bunch.notification.domain.PushDevice
import com.bunch.notification.domain.PushDeviceEnvironment
import com.bunch.notification.domain.PushDeviceRepository
import com.bunch.notification.domain.PushJob
import com.bunch.notification.domain.PushJobRepository
import com.bunch.notification.domain.PushJobStatus
import com.bunch.notification.dto.ApnsResponse
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.dto.PushAlert
import com.bunch.notification.dto.PushAps
import com.bunch.notification.service.ApnsClient
import com.bunch.notification.service.DeviceTokenCipher
import com.bunch.notification.service.PushJobWriter
import com.bunch.notification.service.PushPayloadFactory
import com.bunch.notification.service.PushWorker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "notification.push.device-encryption-key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
    ]
)
@ActiveProfiles("test")
class PushActivityConcurrencyIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var writer: PushJobWriter

    @Autowired
    private lateinit var jobs: PushJobRepository

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var devices: PushDeviceRepository

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var participants: BundleParticipantRepository

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var cipher: DeviceTokenCipher

    @Autowired
    private lateinit var worker: PushWorker

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @MockitoSpyBean
    private lateinit var payloads: PushPayloadFactory

    @MockitoBean
    private lateinit var clock: Clock

    @MockitoBean
    private lateinit var apns: ApnsClient

    @Volatile
    private var now = Instant.parse("2026-11-04T12:00:00Z")

    private val ownSessions = mutableListOf<Pair<Long, UUID>>()

    @BeforeEach
    fun configureClock() {
        `when`(clock.instant()).thenAnswer { now }
        `when`(apns.configured()).thenReturn(true)
    }

    @AfterEach
    fun cleanOwnPushData() {
        jdbc.update("DELETE FROM push_jobs")
        jdbc.update("DELETE FROM push_devices")
        jdbc.update("DELETE FROM push_preferences")
        jdbc.update("DELETE FROM bundle_participants")
        jdbc.update("DELETE FROM bundles")
        ownSessions.forEach { (memberId, sid) -> sessions.delete(sid.toString(), memberId) }
        ownSessions.clear()
    }

    @Test
    fun `activity merge preserves committed device cancellation and its reason`() {
        val (notification, job) = fixture()

        mergeAcross(notification, job) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                writer.cancelPendingForDevice(job.recipientMemberId, requireNotNull(job.deviceId))
            }
            val cancelled = jobs.findById(job.id).orElseThrow()
            assertThat(cancelled.status).isEqualTo(PushJobStatus.CANCELLED)
            assertThat(cancelled.lastErrorCode).isEqualTo("DEVICE_UNREGISTERED")
        }

        val current = jobs.findById(job.id).orElseThrow()
        assertThat(current.status).isEqualTo(PushJobStatus.CANCELLED)
        assertThat(current.lastErrorCode).isEqualTo("DEVICE_UNREGISTERED")
        assertThat(current.attempts).isZero()
        assertThat(current.sentAt).isNull()
        assertThat(current.payload).isEqualTo(job.payload)
        assertThat(current.expiresAt).isEqualTo(job.expiresAt)
    }

    @Test
    fun `activity merge crossing grouping deadline preserves sent job without sending again`() {
        val (notification, job) = fixture()
        val sentAt = requireNotNull(notification.groupUntil).plusSeconds(1)
        respond(job, ApnsResponse(200, null))

        mergeAcross(notification, job) {
            now = sentAt
            assertThat(worker.runOnce()).isEqualTo(1)
            val sent = jobs.findById(job.id).orElseThrow()
            assertThat(sent.status).isEqualTo(PushJobStatus.SENT)
            assertThat(sent.attempts).isEqualTo(1)
            assertThat(sent.sentAt).isEqualTo(sentAt)
        }

        val current = jobs.findById(job.id).orElseThrow()
        assertThat(current.status).isEqualTo(PushJobStatus.SENT)
        assertThat(current.attempts).isEqualTo(1)
        assertThat(current.sentAt).isEqualTo(sentAt)
        assertThat(current.payload).isEqualTo(job.payload)
        assertThat(current.expiresAt).isEqualTo(job.expiresAt)
        assertNoSecondSend(job)
    }

    @Test
    fun `activity merge crossing grouping deadline preserves failed attempt and scheduled retry`() {
        val (notification, job) = fixture()
        val attemptedAt = requireNotNull(notification.groupUntil).plusSeconds(1)
        val retryAt = attemptedAt.plus(Duration.ofMinutes(15))
        respond(job, ApnsResponse(503, "ServiceUnavailable"))

        mergeAcross(notification, job) {
            now = attemptedAt
            assertThat(worker.runOnce()).isEqualTo(1)
            val retrying = jobs.findById(job.id).orElseThrow()
            assertThat(retrying.status).isEqualTo(PushJobStatus.PENDING)
            assertThat(retrying.attempts).isEqualTo(1)
            assertThat(retrying.availableAt).isEqualTo(retryAt)
        }

        val current = jobs.findById(job.id).orElseThrow()
        assertThat(current.status).isEqualTo(PushJobStatus.PENDING)
        assertThat(current.attempts).isEqualTo(1)
        assertThat(current.availableAt).isEqualTo(retryAt)
        assertThat(current.lastErrorCode).isEqualTo("ServiceUnavailable")
        assertThat(current.sentAt).isNull()
        assertThat(current.payload.aps.alert.body).isEqualTo(notification.body)
        assertThat(current.payload.aps.alert.title).isEqualTo(job.payload.aps.alert.title)
        assertThat(current.expiresAt).isEqualTo(job.expiresAt)
        assertNoSecondSend(job)
    }

    private fun mergeAcross(notification: Notification, job: PushJob, concurrentAction: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            // The writer has loaded the pending job, but has not applied the merged payload yet.
            doAnswer { invocation ->
                val payload = invocation.callRealMethod()
                executor.submit { concurrentAction() }.get(10, TimeUnit.SECONDS)
                payload
            }.`when`(payloads).forNotification(job.id, notification, job.payload.aps.alert.title)

            TransactionTemplate(transactionManager).executeWithoutResult {
                writer.refreshMergedActivity(notification)
            }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun respond(job: PushJob, response: ApnsResponse) {
        val device = devices.findById(requireNotNull(job.deviceId)).orElseThrow()
        `when`(
            apns.send(
                device.environment,
                device.topic,
                cipher.decrypt(device.tokenCiphertext),
                job.id,
                job.payload,
                job.expiresAt
            )
        ).thenReturn(response)
    }

    private fun assertNoSecondSend(job: PushJob) {
        assertThat(worker.runOnce()).isZero()
        val device = devices.findById(requireNotNull(job.deviceId)).orElseThrow()
        verify(apns, times(1)).send(
            device.environment,
            device.topic,
            cipher.decrypt(device.tokenCiphertext),
            job.id,
            job.payload,
            job.expiresAt
        )
    }

    private fun fixture(): Pair<Notification, PushJob> {
        val memberId = member()
        val bunchId = create(memberId).id
        val sid = UUID.randomUUID()
        sessions.save(sid.toString(), memberId, "activity-concurrency-test", 3600)
        ownSessions += memberId to sid
        val token = UUID.randomUUID().toString().replace("-", "")
        val device = devices.saveAndFlush(
            PushDevice(
                installationId = UUID.randomUUID(),
                memberId = memberId,
                sessionId = sid,
                environment = PushDeviceEnvironment.SANDBOX,
                topic = "test.bunch",
                tokenCiphertext = cipher.encrypt(token),
                tokenHash = cipher.hash(token),
                authorizationStatus = PushAuthorizationStatus.AUTHORIZED,
                registeredAt = now
            )
        )
        val bundle = bundles.saveAndFlush(Bundle(bunchId = bunchId, createdBy = memberId))
        participants.saveAndFlush(BundleParticipant(bundleId = bundle.id, memberId = memberId))
        val createdAt = now.minusSeconds(599)
        val notification = notifications.saveAndFlush(
            Notification(
                recipientMemberId = memberId,
                bunchId = bunchId,
                type = NotificationType.DRAFT_ACTIVITY,
                dedupeKey = "activity-concurrency:${UUID.randomUUID()}",
                title = "updated activity title",
                body = "merged activity body",
                target = NotificationTarget(NotificationTargetKind.DRAFT, bunchId, bundle.id),
                groupUntil = createdAt.plus(Duration.ofMinutes(10)),
                createdAt = createdAt
            )
        )
        val id = UUID.randomUUID()
        val payload = payloads.forNotification(id, notification, "initial activity title").copy(
            aps = PushAps(PushAlert("initial activity title", "initial activity body"))
        )
        val job = jobs.saveAndFlush(
            PushJob(
                id = id,
                deviceId = device.id,
                recipientMemberId = memberId,
                sessionId = sid,
                bunchId = bunchId,
                notificationId = notification.id,
                dedupeKey = notification.dedupeKey,
                type = notification.type,
                payload = payload,
                availableAt = requireNotNull(notification.groupUntil),
                expiresAt = createdAt.plus(Duration.ofHours(24)),
                createdAt = createdAt
            )
        )
        return notification to job
    }
}
