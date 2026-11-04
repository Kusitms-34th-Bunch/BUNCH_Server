package com.bunch.bunch

import com.bunch.bunch.domain.BunchAccessNoticeRepository
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.MembershipStatus
import com.bunch.bunch.dto.BunchMembershipExitRequest
import com.bunch.bunch.dto.BunchRemovalRequest
import com.bunch.bunch.service.BunchMembershipExitService
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.push.PushDeviceRepository
import com.bunch.notification.domain.push.PushJob
import com.bunch.notification.domain.push.PushJobRepository
import com.bunch.notification.domain.push.PushJobStatus
import com.bunch.notification.dto.PushAlert
import com.bunch.notification.dto.PushAps
import com.bunch.notification.dto.PushPayload
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BunchMembershipExitPushIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var exits: BunchMembershipExitService

    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Autowired
    private lateinit var notices: BunchAccessNoticeRepository

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var devices: PushDeviceRepository

    @Autowired
    private lateinit var jobs: PushJobRepository

    @BeforeEach
    fun clearPushRowsBeforeTest() {
        clearPushRows()
    }

    @AfterEach
    fun clearPushRowsAfterTest() {
        jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_push_job ON push_jobs")
        jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        clearPushRows()
    }

    @Test
    fun `owner leave writes the existing inbox notification and a device job in one transaction`() {
        val owner = member()
        val successorMember = member()
        val bunch = create(owner)
        join(successorMember, issue(owner, bunch.id))
        val successor = memberships.findByMemberIdAndBunchIdAndStatus(successorMember, bunch.id)!!
        val ownerMembership = memberships.findByMemberIdAndBunchIdAndStatus(owner, bunch.id)!!
        device(successorMember)

        exits.leave(
            owner,
            bunch.id,
            UUID.randomUUID(),
            BunchMembershipExitRequest(ownerMembership.id, version(bunch.id), successor.id)
        )

        val inbox = notifications.findAll().single {
            it.recipientMemberId == successorMember && it.type == NotificationType.OWNER_TRANSFERRED
        }
        val job = jobs.findAll().single {
            it.recipientMemberId == successorMember && it.type == NotificationType.OWNER_TRANSFERRED
        }
        assertThat(job.notificationId).isEqualTo(inbox.id)
        assertThat(job.payload.target["kind"]).isEqualTo("ROLE_PERMISSIONS")
        assertThat(job.status).isEqualTo(PushJobStatus.PENDING)
    }

    @Test
    fun `removal creates only a detached notice job cancels reminders and rejoin cancels the removal job`() {
        val owner = member()
        val removed = member()
        val bunch = create(owner)
        val invitation = issue(owner, bunch.id)
        join(removed, invitation)
        val ownerMembership = memberships.findByMemberIdAndBunchIdAndStatus(owner, bunch.id)!!
        val removedMembership = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunch.id)!!
        val device = device(removed)
        val reminder = reminderJob(removed, bunch.id, device)

        exits.remove(
            owner,
            bunch.id,
            removedMembership.id,
            BunchRemovalRequest(ownerMembership.id, version(bunch.id))
        )

        val notice = notices.findAllByRecipientMemberIdAndBunchId(removed, bunch.id).single()
        val removalJob = jobs.findAll().single {
            it.recipientMemberId == removed && it.type == NotificationType.MEMBER_REMOVED
        }
        assertThat(removalJob.notificationId).isNull()
        assertThat(removalJob.payload.target["kind"]).isEqualTo("ACCESS_NOTICE")
        assertThat((removalJob.payload.target["noticeId"] as Number).toLong()).isEqualTo(notice.id)
        assertThat(removalJob.payload.aps.alert.body)
            .isEqualTo("번치 멤버 목록에서 제외되어 더 이상 번치에 참여할 수 없어요.")
        assertThat(notifications.findAll()).noneMatch { it.type == NotificationType.MEMBER_REMOVED }
        assertThat(jobs.findById(reminder.id).orElseThrow().status).isEqualTo(PushJobStatus.CANCELLED)
        assertThat(jobs.findById(reminder.id).orElseThrow().lastErrorCode)
            .isEqualTo("BUNCH_MEMBERSHIP_ENDED")

        val rotated = issue(owner, bunch.id)
        join(removed, rotated, UUID.randomUUID())

        assertThat(jobs.findById(removalJob.id).orElseThrow().status).isEqualTo(PushJobStatus.CANCELLED)
        assertThat(jobs.findById(removalJob.id).orElseThrow().lastErrorCode).isEqualTo("REJOINED")
        assertThat(notices.findAllByRecipientMemberIdAndBunchId(removed, bunch.id)).isEmpty()
        assertThat(memberships.findByMemberIdAndBunchIdAndStatus(removed, bunch.id)!!.role)
            .isEqualTo(MembershipRole.MEMBER)
    }

    @Test
    fun `owner transfer push failure rolls back membership roles inbox and version`() {
        val owner = member()
        val successorMember = member()
        val bunch = create(owner)
        join(successorMember, issue(owner, bunch.id))
        val ownerMembership = memberships.findByMemberIdAndBunchIdAndStatus(owner, bunch.id)!!
        val successor = memberships.findByMemberIdAndBunchIdAndStatus(successorMember, bunch.id)!!
        device(successorMember)
        val beforeVersion = version(bunch.id)
        rejectWrite("bunch_test_reject_push_job", "push_jobs", "INSERT")

        try {
            assertThatThrownBy {
                exits.leave(
                    owner,
                    bunch.id,
                    UUID.randomUUID(),
                    BunchMembershipExitRequest(ownerMembership.id, beforeVersion, successor.id)
                )
            }.isInstanceOf(DataAccessException::class.java)
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_push_job ON push_jobs")
            jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        }

        assertThat(version(bunch.id)).isEqualTo(beforeVersion)
        assertThat(memberships.findById(ownerMembership.id).orElseThrow().status)
            .isEqualTo(MembershipStatus.ACTIVE)
        assertThat(memberships.findById(ownerMembership.id).orElseThrow().role)
            .isEqualTo(MembershipRole.OWNER)
        assertThat(memberships.findById(successor.id).orElseThrow().role)
            .isEqualTo(MembershipRole.MEMBER)
        assertThat(notifications.findAll()).noneMatch { it.type == NotificationType.OWNER_TRANSFERRED }
        assertThat(jobs.findAll()).isEmpty()
    }

    @Test
    fun `removal detached push failure rolls back notice membership version and invitation`() {
        val owner = member()
        val removed = member()
        val bunch = create(owner)
        val invitation = issue(owner, bunch.id)
        join(removed, invitation)
        val ownerMembership = memberships.findByMemberIdAndBunchIdAndStatus(owner, bunch.id)!!
        val removedMembership = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunch.id)!!
        device(removed)
        val beforeVersion = version(bunch.id)
        rejectWrite("bunch_test_reject_push_job", "push_jobs", "INSERT")

        try {
            assertThatThrownBy {
                exits.remove(
                    owner,
                    bunch.id,
                    removedMembership.id,
                    BunchRemovalRequest(ownerMembership.id, beforeVersion)
                )
            }.isInstanceOf(DataAccessException::class.java)
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_push_job ON push_jobs")
            jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        }

        assertThat(version(bunch.id)).isEqualTo(beforeVersion)
        assertThat(memberships.findById(removedMembership.id).orElseThrow().status)
            .isEqualTo(MembershipStatus.ACTIVE)
        assertThat(notices.findAllByRecipientMemberIdAndBunchId(removed, bunch.id)).isEmpty()
        assertThat(issue(owner, bunch.id).code).isEqualTo(invitation.code)
        assertThat(jobs.findAll()).isEmpty()
    }

    private fun device(memberId: Long): Long {
        val installationId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO push_devices(
                installation_id, member_id, session_id, environment, topic,
                token_ciphertext, token_hash, authorization_status, registered_at
            ) VALUES (?, ?, ?, 'SANDBOX', 'test.bunch', ?, ?, 'AUTHORIZED', CURRENT_TIMESTAMP)""",
            installationId,
            memberId,
            sessionId,
            byteArrayOf(1, 2, 3),
            UUID.randomUUID().toString().replace("-", "").repeat(2)
        )
        return jdbc.queryForObject(
            "SELECT id FROM push_devices WHERE installation_id = ?",
            Long::class.java,
            installationId
        )!!
    }

    private fun reminderJob(memberId: Long, bunchId: Long, deviceId: Long): PushJob {
        val id = UUID.randomUUID()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        return jobs.saveAndFlush(
            PushJob(
                id = id,
                deviceId = deviceId,
                recipientMemberId = memberId,
                sessionId = jdbc.queryForObject(
                    "SELECT session_id FROM push_devices WHERE id = ?",
                    UUID::class.java,
                    deviceId
                )!!,
                bunchId = bunchId,
                notificationId = null,
                dedupeKey = "exit-reminder:$id",
                type = NotificationType.COMMENT_REMINDER,
                payload = PushPayload(
                    aps = PushAps(PushAlert("테스트", "테스트")),
                    eventId = id,
                    type = NotificationType.COMMENT_REMINDER,
                    target = mapOf(
                        "kind" to "DRAFT",
                        "bunchId" to bunchId,
                        "bundleId" to 1L
                    )
                ),
                availableAt = now,
                expiresAt = now.plusSeconds(3600),
                createdAt = now
            )
        )
    }

    private fun version(bunchId: Long): Long = jdbc.queryForObject(
        "SELECT membership_version FROM bunches WHERE id = ?",
        Long::class.java,
        bunchId
    )!!

    private fun clearPushRows() {
        jdbc.update("DELETE FROM push_jobs")
        jdbc.update("DELETE FROM push_devices")
        jdbc.update("DELETE FROM push_preferences")
    }
}
