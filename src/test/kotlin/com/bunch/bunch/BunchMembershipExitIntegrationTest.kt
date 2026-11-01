package com.bunch.bunch

import com.bunch.bunch.domain.BunchAccessNoticeRepository
import com.bunch.bunch.domain.BunchActionRequestRepository
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.MembershipStatus
import com.bunch.bunch.dto.BunchMembershipExitRequest
import com.bunch.bunch.dto.BunchRemovalRequest
import com.bunch.bunch.service.BunchMembershipExitService
import com.bunch.bunch.service.BunchMembershipManagementService
import com.bunch.bunch.dto.OwnerTransferRequest
import com.bunch.global.exception.ErrorCode
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.dao.DataAccessException
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BunchMembershipExitIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var exits: BunchMembershipExitService

    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Autowired
    private lateinit var actionRequests: BunchActionRequestRepository

    @Autowired
    private lateinit var accessNotices: BunchAccessNoticeRepository

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var management: BunchMembershipManagementService

    @Test
    fun `member leave is atomic and same key replays without restoring the old membership`() {
        val owner = member()
        val visitor = member()
        val bunch = create(owner)
        val invitation = issue(owner, bunch.id)
        val joinKey = UUID.randomUUID()
        join(visitor, invitation, joinKey)
        val visitorMembership = membership(visitor, bunch.id)
        val request = BunchMembershipExitRequest(
            actorMembershipId = visitorMembership.id,
            expectedMembershipVersion = version(bunch.id)
        )
        val leaveKey = UUID.randomUUID()

        val result = exits.leave(visitor, bunch.id, leaveKey, request)
        val replay = exits.leave(visitor, bunch.id, leaveKey, request)

        assertThat(result).isEqualTo(replay)
        assertThat(result.outcome).isEqualTo("LEFT")
        assertThat(memberships.findById(visitorMembership.id).orElseThrow().status)
            .isEqualTo(MembershipStatus.LEFT)
        assertThat(actionRequests.count()).isEqualTo(1)
        assertError(ErrorCode.FORBIDDEN) {
            exits.leave(visitor, bunch.id, UUID.randomUUID(), request)
        }
        assertError(ErrorCode.FORBIDDEN) {
            invitations.join(visitor, joinKey, invitation.url)
        }

        val rejoined = join(visitor, invitation, UUID.randomUUID())
        val current = membership(visitor, bunch.id)
        assertThat(rejoined.outcome.name).isEqualTo("JOINED")
        assertThat(current.id).isNotEqualTo(visitorMembership.id)
        assertThat(current.role).isEqualTo(MembershipRole.MEMBER)
        assertThat(current.status).isEqualTo(MembershipStatus.ACTIVE)
        assertThat(memberships.findAllByBunchIdAndStatus(bunch.id)).hasSize(2)

        val fallbackOwner = member()
        val fallback = create(fallbackOwner)
        join(visitor, issue(fallbackOwner, fallback.id))
        val replayAfterRejoin = exits.leave(visitor, bunch.id, leaveKey, request)

        assertThat(replayAfterRejoin.defaultBunchId).isEqualTo(fallback.id)
        assertThat(memberships.findById(current.id).orElseThrow().status)
            .isEqualTo(MembershipStatus.ACTIVE)
    }

    @Test
    fun `owner leave requires a successor and transfers ownership before ending the actor`() {
        val owner = member()
        val successorMember = member()
        val bunch = create(owner)
        join(successorMember, issue(owner, bunch.id))
        val ownerMembership = membership(owner, bunch.id)
        val successor = membership(successorMember, bunch.id)
        val currentVersion = version(bunch.id)

        assertError(ErrorCode.BUNCH_OWNER_SUCCESSOR_REQUIRED) {
            exits.leave(
                owner,
                bunch.id,
                UUID.randomUUID(),
                BunchMembershipExitRequest(ownerMembership.id, currentVersion)
            )
        }

        val result = exits.leave(
            owner,
            bunch.id,
            UUID.randomUUID(),
            BunchMembershipExitRequest(ownerMembership.id, currentVersion, successor.id)
        )

        assertThat(result.outcome).isEqualTo("LEFT")
        val endedOwner = memberships.findById(ownerMembership.id).orElseThrow()
        assertThat(endedOwner.status).isEqualTo(MembershipStatus.LEFT)
        assertThat(endedOwner.role).isEqualTo(MembershipRole.MEMBER)
        assertThat(memberships.findById(successor.id).orElseThrow().role)
            .isEqualTo(MembershipRole.OWNER)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_memberships WHERE bunch_id = ? AND status = 'ACTIVE' AND role = 'OWNER'",
            Long::class.java,
            bunch.id
        )).isEqualTo(1L)
        assertThat(notifications.findAll().filter {
            it.recipientMemberId == successorMember && it.type == NotificationType.OWNER_TRANSFERRED
        }).hasSize(1)
    }

    @Test
    fun `removal rotates invitation, stores a private notice, and rejoin uses a new member`() {
        val owner = member()
        val removed = member()
        val bunch = create(owner)
        val oldInvitation = issue(owner, bunch.id)
        val oldJoinKey = UUID.randomUUID()
        join(removed, oldInvitation, oldJoinKey)
        val ownerMembership = membership(owner, bunch.id)
        val removedMembership = membership(removed, bunch.id)
        val currentVersion = version(bunch.id)

        val result = exits.remove(
            owner,
            bunch.id,
            removedMembership.id,
            BunchRemovalRequest(ownerMembership.id, currentVersion)
        )

        assertThat(result.removedMembershipId).isEqualTo(removedMembership.id)
        assertThat(memberships.findById(removedMembership.id).orElseThrow().status)
            .isEqualTo(MembershipStatus.REMOVED)
        assertThat(accessNotices.findAllByRecipientMemberIdAndBunchId(removed, bunch.id))
            .hasSize(1)
        val newInvitation = issue(owner, bunch.id)
        assertThat(newInvitation.code).isNotEqualTo(oldInvitation.code)
        assertError(ErrorCode.FORBIDDEN) {
            invitations.join(removed, oldJoinKey, oldInvitation.url)
        }

        val rejoined = join(removed, newInvitation, UUID.randomUUID())
        val current = membership(removed, bunch.id)
        assertThat(rejoined.outcome.name).isEqualTo("JOINED")
        assertThat(current.id).isNotEqualTo(removedMembership.id)
        assertThat(current.role).isEqualTo(MembershipRole.MEMBER)
        assertThat(accessNotices.findAllByRecipientMemberIdAndBunchId(removed, bunch.id)).isEmpty()
    }

    @Test
    fun `sole owner cannot leave and stale membership version does not change data`() {
        val owner = member()
        val bunch = create(owner)
        val ownerMembership = membership(owner, bunch.id)
        val currentVersion = version(bunch.id)

        assertError(ErrorCode.BUNCH_SOLE_MEMBER_CANNOT_LEAVE) {
            exits.leave(
                owner,
                bunch.id,
                UUID.randomUUID(),
                BunchMembershipExitRequest(ownerMembership.id, currentVersion)
            )
        }
        assertError(ErrorCode.BUNCH_MEMBERSHIP_VERSION_CONFLICT) {
            exits.leave(
                owner,
                bunch.id,
                UUID.randomUUID(),
                BunchMembershipExitRequest(ownerMembership.id, currentVersion - 1, UUID.randomUUID().mostSignificantBits)
            )
        }
        assertThat(version(bunch.id)).isEqualTo(currentVersion)
        assertThat(memberships.findById(ownerMembership.id).orElseThrow().status)
            .isEqualTo(MembershipStatus.ACTIVE)
    }

    @Test
    fun `notice persistence failure rolls back removal, cleanup, version and invitation rotation`() {
        val owner = member()
        val removed = member()
        val bunch = create(owner)
        val oldInvitation = issue(owner, bunch.id)
        join(removed, oldInvitation)
        val ownerMembership = membership(owner, bunch.id)
        val removedMembership = membership(removed, bunch.id)
        val currentVersion = version(bunch.id)
        rejectWrite("bunch_test_reject_access_notice", "bunch_access_notices", "INSERT")

        try {
            org.assertj.core.api.Assertions.assertThatThrownBy {
                exits.remove(
                    owner,
                    bunch.id,
                    removedMembership.id,
                    BunchRemovalRequest(ownerMembership.id, currentVersion)
                )
            }.isInstanceOf(DataAccessException::class.java)
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_access_notice ON bunch_access_notices")
            jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        }

        assertThat(version(bunch.id)).isEqualTo(currentVersion)
        assertThat(memberships.findById(removedMembership.id).orElseThrow().status)
            .isEqualTo(MembershipStatus.ACTIVE)
        assertThat(accessNotices.findAllByRecipientMemberIdAndBunchId(removed, bunch.id)).isEmpty()
        assertThat(issue(owner, bunch.id).code).isEqualTo(oldInvitation.code)
    }

    @Test
    fun `concurrent owner handoff and leave serialize on the member and bunch locks`() {
        val owner = member()
        val successor = member()
        val transferTarget = member()
        val bunch = create(owner)
        join(successor, issue(owner, bunch.id))
        join(transferTarget, issue(owner, bunch.id))
        val ownerMembership = membership(owner, bunch.id)
        val successorMembership = membership(successor, bunch.id)
        val transferTargetMembership = membership(transferTarget, bunch.id)
        val currentVersion = version(bunch.id)

        val results = concurrently(
            listOf(
                {
                    exits.leave(
                        owner,
                        bunch.id,
                        UUID.randomUUID(),
                        BunchMembershipExitRequest(ownerMembership.id, currentVersion, successorMembership.id)
                    )
                },
                {
                    management.transferOwner(
                        owner,
                        bunch.id,
                        UUID.randomUUID(),
                        OwnerTransferRequest(ownerMembership.id, currentVersion, transferTargetMembership.id)
                    )
                }
            )
        )

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.count { it.isFailure }).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_memberships WHERE bunch_id = ? AND status = 'ACTIVE' AND role = 'OWNER'",
            Long::class.java,
            bunch.id
        )).isEqualTo(1L)
    }

    private fun membership(memberId: Long, bunchId: Long): BunchMembership =
        memberships.findByMemberIdAndBunchIdAndStatus(memberId, bunchId)!!

    private fun version(bunchId: Long): Long = jdbc.queryForObject(
        "SELECT membership_version FROM bunches WHERE id = ?",
        Long::class.java,
        bunchId
    )!!
}
