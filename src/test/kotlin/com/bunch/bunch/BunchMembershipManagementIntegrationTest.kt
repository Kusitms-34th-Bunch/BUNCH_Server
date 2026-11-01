package com.bunch.bunch

import com.bunch.bunch.domain.BunchActionRequest
import com.bunch.bunch.domain.BunchActionOperation
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.dto.ChangeMembershipRoleRequest
import com.bunch.bunch.dto.OwnerTransferRequest
import com.bunch.bunch.service.BunchMembershipManagementService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BunchMembershipManagementIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var management: BunchMembershipManagementService

    @Autowired
    private lateinit var membershipRepository: BunchMembershipRepository

    @Autowired
    private lateinit var actionRequests: com.bunch.bunch.domain.BunchActionRequestRepository

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanManagementSessions() {
        jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_role_notification ON notifications")
        jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    @Test
    fun `membership management list exposes membership ids versions and role actions`() {
        val owner = member()
        val bunch = create(owner)
        val coOwner = joined(owner, bunch.id)
        val ordinary = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val coOwnerMembership = membership(coOwner, bunch.id)
        val ordinaryMembership = membership(ordinary, bunch.id)
        management.changeRole(
            owner,
            bunch.id,
            coOwnerMembership.id,
            ChangeMembershipRoleRequest(ownerMembership.id, version(bunch.id), MembershipRole.CO_OWNER)
        )

        val response = listRequest(sessionFor(owner), bunch.id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.bunchId").value(bunch.id))
            .andExpect(jsonPath("$.data.membershipVersion").value(version(bunch.id)))
            .andExpect(jsonPath("$.data.myMembership.membershipId").value(ownerMembership.id))
            .andExpect(jsonPath("$.data.myMembership.role").value("OWNER"))
            .andExpect(jsonPath("$.data.members[0].membershipId").value(ownerMembership.id))
            .andExpect(jsonPath("$.data.members[0].isMe").value(true))
            .andExpect(jsonPath("$.data.members[0].actions.canGrantCoOwner").value(false))
            .andExpect(jsonPath("$.data.members[1].membershipId").value(coOwnerMembership.id))
            .andExpect(jsonPath("$.data.members[1].role").value("CO_OWNER"))
            .andExpect(jsonPath("$.data.members[1].actions.canRevokeCoOwner").value(true))
            .andExpect(jsonPath("$.data.members[1].actions.canTransferOwner").value(true))
            .andExpect(jsonPath("$.data.members[2].membershipId").value(ordinaryMembership.id))
            .andExpect(jsonPath("$.data.members[2].actions.canGrantCoOwner").value(true))
            .andExpect(jsonPath("$.data.members[2].actions.canRemove").value(true))
            .andReturn()

        assertThat(response.response.status).isEqualTo(200)
    }

    @Test
    fun `owner can grant and revoke co owner with metadata and target notification`() {
        val owner = member()
        val bunch = create(owner)
        val target = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val before = version(bunch.id)

        val granted = management.changeRole(
            owner,
            bunch.id,
            targetMembership.id,
            ChangeMembershipRoleRequest(ownerMembership.id, before, MembershipRole.CO_OWNER)
        )

        assertThat(granted.membershipVersion).isEqualTo(before + 1)
        assertThat(jdbc.queryForMap("SELECT * FROM bunch_memberships WHERE id = ?", targetMembership.id))
            .containsEntry("role", "CO_OWNER")
            .containsEntry("role_granted_by", owner)
            .extracting("role_granted_at")
            .isNotNull
        assertThat(roleNotifications(target, NotificationType.CO_OWNER_GRANTED)).hasSize(1)
        assertThat(roleNotifications(owner, NotificationType.CO_OWNER_GRANTED)).isEmpty()

        val revoked = management.changeRole(
            owner,
            bunch.id,
            targetMembership.id,
            ChangeMembershipRoleRequest(ownerMembership.id, granted.membershipVersion, MembershipRole.MEMBER)
        )

        assertThat(revoked.membershipVersion).isEqualTo(before + 2)
        assertThat(jdbc.queryForObject(
            "SELECT role FROM bunch_memberships WHERE id = ?",
            String::class.java,
            targetMembership.id
        )).isEqualTo("MEMBER")
        assertThat(roleNotifications(target, NotificationType.CO_OWNER_REVOKED)).hasSize(1)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_memberships WHERE bunch_id = ? AND status = 'ACTIVE' AND role = 'OWNER'",
            Long::class.java,
            bunch.id
        )).isEqualTo(1L)
    }

    @Test
    fun `same target role is a no op while stale real mutation conflicts`() {
        val owner = member()
        val bunch = create(owner)
        val target = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val current = version(bunch.id)

        val noOp = management.changeRole(
            owner,
            bunch.id,
            targetMembership.id,
            ChangeMembershipRoleRequest(ownerMembership.id, current, MembershipRole.MEMBER)
        )
        assertThat(noOp.membershipVersion).isEqualTo(current)
        assertThat(noOp.changedMembershipIds).isEmpty()
        assertThat(roleNotifications(target, NotificationType.CO_OWNER_GRANTED)).isEmpty()

        val granted = management.changeRole(
            owner,
            bunch.id,
            targetMembership.id,
            ChangeMembershipRoleRequest(ownerMembership.id, current, MembershipRole.CO_OWNER)
        )
        val replay = management.changeRole(
            owner,
            bunch.id,
            targetMembership.id,
            ChangeMembershipRoleRequest(ownerMembership.id, current, MembershipRole.CO_OWNER)
        )
        assertThat(replay.membershipVersion).isEqualTo(granted.membershipVersion)
        assertThat(replay.changedMembershipIds).isEmpty()
        assertThat(roleNotifications(target, NotificationType.CO_OWNER_GRANTED)).hasSize(1)

        assertError(ErrorCode.BUNCH_MEMBERSHIP_VERSION_CONFLICT) {
            management.changeRole(
                owner,
                bunch.id,
                targetMembership.id,
                ChangeMembershipRoleRequest(ownerMembership.id, current, MembershipRole.MEMBER)
            )
        }
    }

    @Test
    fun `only owner can manage roles and cross bunch memberships are not exposed`() {
        val owner = member()
        val bunch = create(owner)
        val coOwner = joined(owner, bunch.id)
        val target = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val coOwnerMembership = membership(coOwner, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val granted = management.changeRole(
            owner,
            bunch.id,
            coOwnerMembership.id,
            ChangeMembershipRoleRequest(ownerMembership.id, version(bunch.id), MembershipRole.CO_OWNER)
        )

        assertError(ErrorCode.FORBIDDEN) {
            management.changeRole(
                coOwner,
                bunch.id,
                targetMembership.id,
                ChangeMembershipRoleRequest(coOwnerMembership.id, granted.membershipVersion, MembershipRole.CO_OWNER)
            )
        }
        assertError(ErrorCode.INVALID_INPUT) {
            management.changeRole(
                owner,
                bunch.id,
                ownerMembership.id,
                ChangeMembershipRoleRequest(ownerMembership.id, granted.membershipVersion, MembershipRole.MEMBER)
            )
        }

        val otherOwner = member()
        val otherBunch = create(otherOwner)
        val otherTarget = membership(otherOwner, otherBunch.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) {
            management.changeRole(
                owner,
                bunch.id,
                otherTarget.id,
                ChangeMembershipRoleRequest(ownerMembership.id, granted.membershipVersion, MembershipRole.CO_OWNER)
            )
        }
    }

    @Test
    fun `owner transfer records one replayable result and one target notification`() {
        val owner = member()
        val bunch = create(owner)
        val target = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val key = UUID.randomUUID()
        val request = OwnerTransferRequest(ownerMembership.id, version(bunch.id), targetMembership.id)

        val result = management.transferOwner(owner, bunch.id, key, request)
        val replay = management.transferOwner(owner, bunch.id, key, request)

        assertThat(replay).isEqualTo(result)
        assertThat(actionRequests.count()).isEqualTo(1)
        assertThat(roleNotifications(target, NotificationType.OWNER_TRANSFERRED)).hasSize(1)
        assertThat(roleNotifications(owner, NotificationType.OWNER_TRANSFERRED)).isEmpty()
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_memberships WHERE bunch_id = ? AND status = 'ACTIVE' AND role = 'OWNER'",
            Long::class.java,
            bunch.id
        )).isEqualTo(1L)
        assertThat(jdbc.queryForObject(
            "SELECT role FROM bunch_memberships WHERE id = ?",
            String::class.java,
            ownerMembership.id
        )).isEqualTo("MEMBER")
        assertThat(jdbc.queryForObject(
            "SELECT role_granted_by FROM bunch_memberships WHERE id = ?",
            Long::class.java,
            targetMembership.id
        )).isEqualTo(owner)

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) {
            management.transferOwner(
                owner,
                bunch.id,
                key,
                request.copy(targetMembershipId = ownerMembership.id)
            )
        }
    }

    @Test
    fun `expired owner transfer key is removed before a new success is recorded`() {
        val owner = member()
        val bunch = create(owner)
        val target = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val key = UUID.randomUUID()
        actionRequests.saveAndFlush(
            BunchActionRequest(
                memberId = owner,
                bunchId = bunch.id,
                actorMembershipId = ownerMembership.id,
                operation = BunchActionOperation.OWNER_TRANSFER,
                requestKey = key,
                fingerprint = "0".repeat(64),
                result = mapOf("bunchId" to bunch.id),
                createdAt = Instant.now().minusSeconds(60),
                expiresAt = Instant.now().minusSeconds(1)
            )
        )

        management.transferOwner(
            owner,
            bunch.id,
            key,
            OwnerTransferRequest(ownerMembership.id, version(bunch.id), targetMembership.id)
        )

        assertThat(actionRequests.count()).isEqualTo(1)
        assertThat(actionRequests.findAll().single().expiresAt).isAfter(Instant.now())
    }

    @Test
    fun `notification failure rolls back role change and membership version`() {
        val owner = member()
        val bunch = create(owner)
        val target = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val before = version(bunch.id)
        rejectWrite("bunch_test_reject_role_notification", "notifications", "INSERT")

        assertThatThrownBy {
            management.changeRole(
                owner,
                bunch.id,
                targetMembership.id,
                ChangeMembershipRoleRequest(ownerMembership.id, before, MembershipRole.CO_OWNER)
            )
        }.isInstanceOf(DataAccessException::class.java)

        assertThat(version(bunch.id)).isEqualTo(before)
        assertThat(jdbc.queryForObject(
            "SELECT role FROM bunch_memberships WHERE id = ?",
            String::class.java,
            targetMembership.id
        )).isEqualTo("MEMBER")
        assertThat(roleNotifications(target, NotificationType.CO_OWNER_GRANTED)).isEmpty()
    }

    @Test
    fun `concurrent transfers leave exactly one active owner`() {
        val owner = member()
        val bunch = create(owner)
        val firstTarget = joined(owner, bunch.id)
        val secondTarget = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val firstMembership = membership(firstTarget, bunch.id)
        val secondMembership = membership(secondTarget, bunch.id)
        val expectedVersion = version(bunch.id)

        val results = concurrently(
            listOf(firstMembership.id, secondMembership.id).map { targetMembershipId ->
                {
                    management.transferOwner(
                        owner,
                        bunch.id,
                        UUID.randomUUID(),
                        OwnerTransferRequest(ownerMembership.id, expectedVersion, targetMembershipId)
                    )
                }
            }
        )

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.count { it.isFailure }).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_memberships WHERE bunch_id = ? AND status = 'ACTIVE' AND role = 'OWNER'",
            Long::class.java,
            bunch.id
        )).isEqualTo(1L)
    }

    private fun joined(ownerId: Long, bunchId: Long): Long {
        val memberId = member()
        join(memberId, issue(ownerId, bunchId))
        return memberId
    }

    private fun membership(memberId: Long, bunchId: Long) =
        membershipRepository.findByMemberIdAndBunchIdAndStatus(memberId, bunchId)!!

    private fun version(bunchId: Long): Long = jdbc.queryForObject(
        "SELECT membership_version FROM bunches WHERE id = ?",
        Long::class.java,
        bunchId
    )!!

    private fun roleNotifications(memberId: Long, type: NotificationType) =
        notifications.findAll().filter { it.recipientMemberId == memberId && it.type == type }

    private fun listRequest(auth: Auth, bunchId: Long): ResultActions = mvc.perform(
        get("/api/v1/bunches/$bunchId/memberships")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${auth.accessToken}")
    )

    private fun sessionFor(memberId: Long): Auth {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return Auth(memberId, pair.accessToken)
    }

    private data class Auth(val memberId: Long, val accessToken: String)
}
