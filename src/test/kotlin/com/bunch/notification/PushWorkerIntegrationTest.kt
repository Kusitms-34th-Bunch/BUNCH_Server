package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchBlock
import com.bunch.bunch.domain.BunchBlockRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleComment
import com.bunch.bundle.domain.BundleCommentRepository
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.push.PushAuthorizationStatus
import com.bunch.notification.domain.push.PushDevice
import com.bunch.notification.domain.push.PushDeviceEnvironment
import com.bunch.notification.domain.push.PushDeviceRepository
import com.bunch.notification.domain.push.PushJob
import com.bunch.notification.domain.push.PushJobRepository
import com.bunch.notification.domain.push.PushJobStatus
import com.bunch.notification.dto.ApnsResponse
import com.bunch.notification.dto.PushAlert
import com.bunch.notification.dto.PushAps
import com.bunch.notification.dto.PushPayload
import com.bunch.notification.service.ApnsClient
import com.bunch.notification.service.DeviceTokenCipher
import com.bunch.notification.service.PushWorker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = [
    "notification.push.device-encryption-key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
])
@ActiveProfiles("test")
class PushWorkerIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var worker: PushWorker

    @Autowired
    private lateinit var jobs: PushJobRepository

    @Autowired
    private lateinit var devices: PushDeviceRepository

    @Autowired
    private lateinit var cipher: DeviceTokenCipher

    @Autowired
    private lateinit var blocks: BunchBlockRepository

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var participants: BundleParticipantRepository

    @Autowired
    private lateinit var comments: BundleCommentRepository

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @MockitoBean
    private lateinit var apns: ApnsClient

    @MockitoBean
    private lateinit var clock: Clock

    private var now = Instant.parse("2026-11-02T01:00:00Z")
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
        ownSessions.forEach { (memberId, sid) -> sessions.delete(sid.toString(), memberId) }
        ownSessions.clear()
    }

    @Test
    fun `attempt is committed before HTTP and accepted job is sent once`() {
        val job = fixture()
        respond(job, ApnsResponse(200, null)) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(jobs.findById(job.id).orElseThrow().attempts).isEqualTo(1)
        }

        assertThat(worker.runOnce()).isEqualTo(1)
        assertThat(worker.runOnce()).isZero()

        val sent = jobs.findById(job.id).orElseThrow()
        assertThat(sent.status).isEqualTo(PushJobStatus.SENT)
        assertThat(sent.sentAt).isEqualTo(now)
        assertThat(sent.attempts).isEqualTo(1)
    }

    @Test
    fun `transient failures retry after fifteen minutes and stop at five attempts`() {
        val job = fixture()
        respond(job, ApnsResponse(503, "ServiceUnavailable"))
        repeat(5) { index ->
            assertThat(worker.runOnce()).isEqualTo(1)
            val current = jobs.findById(job.id).orElseThrow()
            assertThat(current.attempts).isEqualTo(index + 1)
            assertThat(current.status).isEqualTo(if (index == 4) PushJobStatus.FAILED else PushJobStatus.PENDING)
            assertThat(worker.runOnce()).isZero()
            now = now.plus(Duration.ofMinutes(15))
        }
    }

    @Test
    fun `retry after is respected and provider failure does not delete the device`() {
        val job = fixture()
        val next = now.plus(Duration.ofMinutes(40))
        respond(job, ApnsResponse(429, "TooManyRequests", next))
        worker.runOnce()
        assertThat(jobs.findById(job.id).orElseThrow().availableAt).isEqualTo(next)
        now = now.plus(Duration.ofMinutes(15))
        assertThat(worker.runOnce()).isZero()
        now = next
        respond(job, ApnsResponse(403, "InvalidProviderToken"))
        worker.runOnce()
        assertThat(jobs.findById(job.id).orElseThrow().status).isEqualTo(PushJobStatus.FAILED)
        assertThat(devices.existsById(requireNotNull(job.deviceId))).isTrue()
    }

    @Test
    fun `expired jobs and lost sessions never reach the provider`() {
        val expired = fixture(createdAt = now.minus(Duration.ofHours(25)))
        val signedOut = fixture()
        sessions.delete(signedOut.sessionId.toString(), signedOut.recipientMemberId)

        assertThat(worker.runOnce()).isZero()

        assertThat(jobs.findById(expired.id).orElseThrow().status).isEqualTo(PushJobStatus.FAILED)
        assertThat(jobs.findById(signedOut.id).orElseThrow().status).isEqualTo(PushJobStatus.CANCELLED)
        assertThat(jobs.findAll().sumOf { it.attempts }).isZero()
    }

    @Test
    fun `unregistered response removes only the token registration that was attempted`() {
        val job = fixture()
        respond(job, ApnsResponse(410, "Unregistered"))
        worker.runOnce()
        assertThat(devices.existsById(requireNotNull(job.deviceId))).isFalse()
        assertThat(jobs.findById(job.id).orElseThrow().status).isEqualTo(PushJobStatus.FAILED)
        assertThat(jobs.findById(job.id).orElseThrow().deviceId).isNull()
    }

    @Test
    fun `old provider response preserves a replacement registration`() {
        val job = fixture()
        respond(job, ApnsResponse(410, "Unregistered")) {
            val current = devices.findById(requireNotNull(job.deviceId)).orElseThrow()
            current.tokenHash = cipher.hash("ccdd")
            current.tokenCiphertext = cipher.encrypt("ccdd")
            current.registeredAt = current.registeredAt.plusSeconds(1)
            devices.saveAndFlush(current)
        }
        worker.runOnce()
        val current = devices.findById(requireNotNull(job.deviceId)).orElseThrow()
        assertThat(cipher.decrypt(current.tokenCiphertext)).isEqualTo("ccdd")
        assertThat(jobs.findById(job.id).orElseThrow().status).isEqualTo(PushJobStatus.FAILED)
    }

    @Test
    fun `missing provider configuration leaves pending jobs unconsumed`() {
        val job = fixture()
        `when`(apns.configured()).thenReturn(false)
        assertThat(worker.runOnce()).isZero()
        assertThat(jobs.findById(job.id).orElseThrow().attempts).isZero()
        assertThat(jobs.findById(job.id).orElseThrow().status).isEqualTo(PushJobStatus.PENDING)
    }

    @Test
    fun `left member job is cancelled before APNs`() {
        val job = fixture()
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ? AND member_id = ?",
            job.bunchId,
            job.recipientMemberId
        )

        assertThat(worker.runOnce()).isZero()
        assertCancelledWithoutProvider(job, "RecipientUnavailable")
    }

    @Test
    fun `job of a category switched off after it was queued is cancelled before APNs`() {
        val job = fixture(type = NotificationType.MEMBER_JOINED)
        preference(job.recipientMemberId, enabled = true, categories = """{"MEMBER_ROLE":false}""")

        assertThat(worker.runOnce()).isZero()
        assertCancelledWithoutProvider(job, "RecipientUnavailable")
    }

    @Test
    fun `job of another category is still sent when one category is off`() {
        val job = fixture(type = NotificationType.MEMBER_JOINED)
        preference(job.recipientMemberId, enabled = true, categories = """{"FOLLOW":false}""")
        respond(job, ApnsResponse(200, null))

        assertThat(worker.runOnce()).isEqualTo(1)
        assertThat(jobs.findById(job.id).orElseThrow().status).isEqualTo(PushJobStatus.SENT)
    }

    @Test
    fun `job of a member without settings is still sent`() {
        val job = fixture(type = NotificationType.MEMBER_JOINED)
        respond(job, ApnsResponse(200, null))

        assertThat(worker.runOnce()).isEqualTo(1)
        assertThat(jobs.findById(job.id).orElseThrow().status).isEqualTo(PushJobStatus.SENT)
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationType::class,
        names = ["MEMBER_REMOVED", "BUNCH_DELETED", "BUNCH_VISIBILITY_CHANGED"]
    )
    fun `always sent notices are sent while the app push is on even if every category is off`(
        type: NotificationType
    ) {
        val job = fixture(type = type)
        preference(
            job.recipientMemberId,
            enabled = true,
            categories = """{"BUNDLE_NEWS":false,"MEMBER_ROLE":false,"FOLLOW":false,"REACTION":false}"""
        )
        respond(job, ApnsResponse(200, null))

        assertThat(worker.runOnce()).isEqualTo(1)
        assertThat(jobs.findById(job.id).orElseThrow().status).isEqualTo(PushJobStatus.SENT)
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationType::class,
        names = ["MEMBER_REMOVED", "BUNCH_DELETED", "BUNCH_VISIBILITY_CHANGED"]
    )
    fun `always sent notices are cancelled when the app push is off`(type: NotificationType) {
        val job = fixture(type = type)
        preference(job.recipientMemberId, enabled = false, categories = "{}")

        assertThat(worker.runOnce()).isZero()
        assertCancelledWithoutProvider(job, "RecipientUnavailable")
    }

    @Test
    fun `the removed growth notice is never sent`() {
        val job = fixture(type = NotificationType.CHARACTER_GROWN) { _, bunchId ->
            mapOf("kind" to NotificationTargetKind.BUNCH_HOME.name, "bunchId" to bunchId)
        }

        assertThat(worker.runOnce()).isZero()
        assertCancelledWithoutProvider(job, "RecipientUnavailable")
    }

    @Test
    fun `reverse social block cancels other bunch profile job before APNs`() {
        val targetMemberId = member()
        val targetBunchId = create(targetMemberId).id
        val job = fixture(type = NotificationType.BUNCH_FOLLOWED) { _, bunchId ->
            mapOf(
                "kind" to NotificationTargetKind.OTHER_BUNCH_PROFILE.name,
                "bunchId" to bunchId,
                "targetBunchId" to targetBunchId
            )
        }
        blocks.saveAndFlush(
            BunchBlock(
                blockerBunchId = targetBunchId,
                blockedBunchId = job.bunchId,
                createdByMemberId = targetMemberId,
                createdAt = now
            )
        )

        assertThat(worker.runOnce()).isZero()
        assertCancelledWithoutProvider(job, "RecipientUnavailable")
    }

    @Test
    fun `completed comment reminder is cancelled before APNs`() {
        val job = fixture(type = NotificationType.COMMENT_REMINDER) { memberId, bunchId ->
            val bundle = bundles.saveAndFlush(Bundle(bunchId = bunchId, createdBy = memberId))
            participants.saveAndFlush(BundleParticipant(bundleId = bundle.id, memberId = memberId))
            comments.saveAndFlush(
                BundleComment(bundleId = bundle.id, memberId = memberId, content = "작성 완료")
            )
            mapOf(
                "kind" to NotificationTargetKind.DRAFT.name,
                "bunchId" to bunchId,
                "bundleId" to bundle.id
            )
        }

        assertThat(worker.runOnce()).isZero()
        assertCancelledWithoutProvider(job, "RecipientUnavailable")
    }

    @Test
    fun `cancelled comment reminder is cancelled before APNs`() {
        val job = fixture(type = NotificationType.COMMENT_REMINDER) { memberId, bunchId ->
            val bundle = bundles.saveAndFlush(Bundle(bunchId = bunchId, createdBy = memberId))
            participants.saveAndFlush(
                BundleParticipant(
                    bundleId = bundle.id,
                    memberId = memberId,
                    remindersCancelledAt = now
                )
            )
            mapOf(
                "kind" to NotificationTargetKind.DRAFT.name,
                "bunchId" to bunchId,
                "bundleId" to bundle.id
            )
        }

        assertThat(worker.runOnce()).isZero()
        assertCancelledWithoutProvider(job, "RecipientUnavailable")
    }

    @Test
    fun `external reply delivery uses inbox context even after current bunch changes`() {
        val job = fixture(type = NotificationType.SOCIAL_COMMENT_CREATED) { recipient, inbox ->
            val owner = member()
            val ownerBunch = create(owner).id
            jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", ownerBunch)
            val bundle = bundles.saveAndFlush(
                Bundle(
                    bunchId = ownerBunch,
                    createdBy = owner,
                    status = BundleStatus.PUBLISHED,
                    visibility = Visibility.PUBLIC
                )
            )
            create(recipient)
            mapOf(
                "kind" to NotificationTargetKind.BUNDLE_COMMENTS.name,
                "bunchId" to inbox,
                "bundleId" to bundle.id,
                "targetBunchId" to ownerBunch,
                "sourceBunchId" to ownerBunch
            )
        }
        respond(job, ApnsResponse(200, null))

        assertThat(worker.runOnce()).isEqualTo(1)
        assertThat(jobs.findById(job.id).orElseThrow().status).isEqualTo(PushJobStatus.SENT)
    }

    @Test
    fun `source block after like enqueue cancels pending delivery`() {
        val sourceOwner = member()
        val source = create(sourceOwner).id
        val job = fixture(type = NotificationType.BUNDLE_LIKED) { recipient, inbox ->
            val bundle = bundles.saveAndFlush(
                Bundle(
                    bunchId = inbox,
                    createdBy = recipient,
                    status = BundleStatus.PUBLISHED,
                    visibility = Visibility.PUBLIC
                )
            )
            mapOf(
                "kind" to NotificationTargetKind.BUNDLE.name,
                "bunchId" to inbox,
                "bundleId" to bundle.id,
                "sourceBunchId" to source
            )
        }
        blocks.saveAndFlush(
            BunchBlock(
                blockerBunchId = source,
                blockedBunchId = job.bunchId,
                createdByMemberId = sourceOwner,
                createdAt = now
            )
        )

        assertThat(worker.runOnce()).isZero()
        assertCancelledWithoutProvider(job, "RecipientUnavailable")
    }

    private fun respond(job: PushJob, response: ApnsResponse, duringHttp: () -> Unit = {}) {
        `when`(apns.send(
            PushDeviceEnvironment.SANDBOX, "test.bunch",
            cipher.decrypt(devices.findById(requireNotNull(job.deviceId)).orElseThrow().tokenCiphertext),
            job.id, job.payload, job.expiresAt
        )).thenAnswer {
            duringHttp()
            response
        }
    }

    private fun preference(memberId: Long, enabled: Boolean, categories: String) {
        jdbc.update(
            "INSERT INTO push_preferences(member_id, enabled, categories, updated_at) " +
                "VALUES (?, ?, ?::jsonb, CURRENT_TIMESTAMP)",
            memberId,
            enabled,
            categories
        )
    }

    private fun assertCancelledWithoutProvider(job: PushJob, reason: String) {
        val current = jobs.findById(job.id).orElseThrow()
        assertThat(current.status).isEqualTo(PushJobStatus.CANCELLED)
        assertThat(current.lastErrorCode).isEqualTo(reason)
        val device = devices.findById(requireNotNull(current.deviceId)).orElseThrow()
        org.mockito.Mockito.verify(apns, org.mockito.Mockito.never()).send(
            device.environment,
            device.topic,
            cipher.decrypt(device.tokenCiphertext),
            current.id,
            current.payload,
            current.expiresAt
        )
    }

    private fun fixture(
        createdAt: Instant = now,
        type: NotificationType = NotificationType.MEMBER_JOINED,
        target: (memberId: Long, bunchId: Long) -> Map<String, Any?> = { _, bunchId ->
            mapOf("kind" to NotificationTargetKind.MEMBERS.name, "bunchId" to bunchId)
        }
    ): PushJob {
        val memberId = member()
        val bunchId = create(memberId).id
        val sid = UUID.randomUUID()
        sessions.save(sid.toString(), memberId, "worker-test", 3600)
        ownSessions += memberId to sid
        val token = UUID.randomUUID().toString().replace("-", "")
        val device = devices.saveAndFlush(PushDevice(
            installationId = UUID.randomUUID(), memberId = memberId, sessionId = sid,
            environment = PushDeviceEnvironment.SANDBOX, topic = "test.bunch",
            tokenCiphertext = cipher.encrypt(token), tokenHash = cipher.hash(token),
            authorizationStatus = PushAuthorizationStatus.AUTHORIZED, registeredAt = now
        ))
        val id = UUID.randomUUID()
        jobs.saveAndFlush(PushJob(
            id = id, deviceId = device.id, recipientMemberId = memberId, sessionId = sid,
            bunchId = bunchId, notificationId = null, dedupeKey = "worker:$id", type = type,
            payload = PushPayload(
                PushAps(PushAlert("제목", "내용")), id, type, target(memberId, bunchId)
            ),
            availableAt = now, expiresAt = createdAt.plus(Duration.ofHours(24)), createdAt = createdAt
        ))
        return jobs.findById(id).orElseThrow()
    }
}
