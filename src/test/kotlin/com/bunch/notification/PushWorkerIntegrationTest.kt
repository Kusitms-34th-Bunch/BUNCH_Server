package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.PushAuthorizationStatus
import com.bunch.notification.domain.PushDevice
import com.bunch.notification.domain.PushDeviceEnvironment
import com.bunch.notification.domain.PushDeviceRepository
import com.bunch.notification.domain.PushJob
import com.bunch.notification.domain.PushJobRepository
import com.bunch.notification.domain.PushJobStatus
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

    private fun fixture(createdAt: Instant = now): PushJob {
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
            bunchId = bunchId, notificationId = null, dedupeKey = "worker:$id", type = NotificationType.MEMBER_JOINED,
            payload = PushPayload(
                PushAps(PushAlert("제목", "내용")), id, NotificationType.MEMBER_JOINED,
                mapOf("kind" to "MEMBERS", "bunchId" to bunchId)
            ),
            availableAt = now, expiresAt = createdAt.plus(Duration.ofHours(24)), createdAt = createdAt
        ))
        return jobs.findById(id).orElseThrow()
    }
}
