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
import com.fasterxml.jackson.databind.ObjectMapper
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @MockitoBean
    private lateinit var clock: Clock

    private val sessionIds = mutableListOf<Pair<String, Long>>()
    private val testNow = Instant.parse("2026-11-02T00:00:00.123456Z")

    @BeforeEach
    fun setManagementClock() {
        `when`(clock.instant()).thenReturn(testNow)
    }

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
    fun `management HTTP contract validates authentication signup ids versions json and headers`() {
        val owner = member()
        val bunch = create(owner)
        val target = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val ownerAuth = sessionFor(owner)

        mvc.perform(get("/api/v1/bunches/${bunch.id}/memberships"))
            .expectError(ErrorCode.UNAUTHORIZED)

        val incompleteAuth = sessionFor(member(completed = false))
        listRequest(incompleteAuth, bunch.id).expectError(ErrorCode.SIGNUP_INCOMPLETE)

        listOf(0, -1).forEach { invalidBunchId ->
            listRequest(ownerAuth, invalidBunchId.toLong()).expectError(ErrorCode.INVALID_INPUT)
        }

        val currentVersion = version(bunch.id)
        val validRole = ChangeMembershipRoleRequest(
            actorMembershipId = ownerMembership.id,
            expectedMembershipVersion = currentVersion,
            role = MembershipRole.CO_OWNER
        )
        listOf(0, -1).forEach { invalidMembershipId ->
            mvc.perform(
                put("/api/v1/bunches/${bunch.id}/memberships/$invalidMembershipId/role")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(validRole))
            ).expectError(ErrorCode.INVALID_INPUT)
        }
        mvc.perform(
            put("/api/v1/bunches/${bunch.id}/memberships/${targetMembership.id}/role")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(validRole.copy(actorMembershipId = 0)))
        ).expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(
            put("/api/v1/bunches/${bunch.id}/memberships/${targetMembership.id}/role")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(validRole.copy(expectedMembershipVersion = -1)))
        ).expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(
            put("/api/v1/bunches/${bunch.id}/memberships/${targetMembership.id}/role")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-json".toByteArray())
        ).expectError(ErrorCode.INVALID_JSON)

        // Role changes deliberately do not require Idempotency-Key.
        mvc.perform(
            put("/api/v1/bunches/${bunch.id}/memberships/${targetMembership.id}/role")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(validRole))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.membershipVersion").value(currentVersion + 1))
            .andExpect(jsonPath("$.error").isEmpty)

        mvc.perform(
            put("/api/v1/bunches/${bunch.id}/memberships/${targetMembership.id}/role")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(validRole.copy(role = MembershipRole.MEMBER)))
        ).expectError(ErrorCode.BUNCH_MEMBERSHIP_VERSION_CONFLICT)

        val transfer = OwnerTransferRequest(ownerMembership.id, version(bunch.id), targetMembership.id)
        val transferPath = "/api/v1/bunches/${bunch.id}/owner-transfer"
        mvc.perform(
            post(transferPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(transfer))
        ).expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(
            post(transferPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(transfer))
        ).expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(
            post("/api/v1/bunches/-1/owner-transfer")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(transfer))
        ).expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(
            post(transferPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(transfer.copy(targetMembershipId = -1)))
        ).expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(
            post(transferPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(transfer.copy(expectedMembershipVersion = -1)))
        ).expectError(ErrorCode.INVALID_INPUT)

        val key = UUID.randomUUID().toString()
        val first = mvc.perform(
            post(transferPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(transfer))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andReturn().response.contentAsString
        val replay = mvc.perform(
            post(transferPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ownerAuth.accessToken}")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(transfer))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andReturn().response.contentAsString
        assertThat(mapper.readTree(replay).path("data")).isEqualTo(mapper.readTree(first).path("data"))
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
        assertThat(roleNotifications(target, NotificationType.CO_OWNER_GRANTED).single().target.kind)
            .isEqualTo(NotificationTargetKind.ROLE_PERMISSIONS)
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
    fun `concurrent owner transfer retries with one key apply once and replay one result`() {
        val owner = member()
        val bunch = create(owner)
        val target = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val key = UUID.randomUUID()
        val request = OwnerTransferRequest(ownerMembership.id, version(bunch.id), targetMembership.id)

        val results = concurrently(
            listOf(
                { management.transferOwner(owner, bunch.id, key, request) },
                { management.transferOwner(owner, bunch.id, key, request) }
            )
        )

        assertThat(results).allMatch { it.isSuccess }
        assertThat(results.map { it.getOrThrow() }.distinct()).hasSize(1)
        assertThat(actionRequests.count()).isEqualTo(1)
        assertThat(roleNotifications(target, NotificationType.OWNER_TRANSFERRED)).hasSize(1)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_memberships WHERE bunch_id = ? AND status = 'ACTIVE' AND role = 'OWNER'",
            Long::class.java,
            bunch.id
        )).isEqualTo(1L)
    }

    @Test
    fun `previous membership actor id is rejected after the member rejoins`() {
        val owner = member()
        val bunch = create(owner)
        val former = joined(owner, bunch.id)
        val target = joined(owner, bunch.id)
        val formerMembership = membership(former, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val invitation = issue(owner, bunch.id)

        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = ? WHERE id = ?",
            Timestamp.from(testNow),
            formerMembership.id
        )
        join(former, invitation)
        val currentMembership = membership(former, bunch.id)
        assertThat(currentMembership.id).isNotEqualTo(formerMembership.id)

        val formerAuth = sessionFor(former)
        mvc.perform(
            put("/api/v1/bunches/${bunch.id}/memberships/${targetMembership.id}/role")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${formerAuth.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsBytes(
                        ChangeMembershipRoleRequest(
                            actorMembershipId = formerMembership.id,
                            expectedMembershipVersion = version(bunch.id),
                            role = MembershipRole.CO_OWNER
                        )
                    )
                )
        ).expectError(ErrorCode.FORBIDDEN)
    }

    @Test
    fun `owner transfer replay survives leave and bunch deletion while a new key rechecks authority`() {
        val owner = member()
        val bunch = create(owner)
        val target = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val key = UUID.randomUUID()
        val request = OwnerTransferRequest(ownerMembership.id, version(bunch.id), targetMembership.id)
        val result = management.transferOwner(owner, bunch.id, key, request)

        assertError(ErrorCode.FORBIDDEN) {
            management.transferOwner(owner, bunch.id, UUID.randomUUID(), request)
        }

        // #25 owns the leave service; this DB transition proves the shared replay row is independent of it.
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = ? WHERE id = ?",
            Timestamp.from(testNow),
            ownerMembership.id
        )
        assertThat(management.transferOwner(owner, bunch.id, key, request)).isEqualTo(result)

        // #50 owns deletion; V16 intentionally has no bunch FK so the successful result remains replayable.
        jdbc.update("DELETE FROM bunches WHERE id = ?", bunch.id)
        assertThat(management.transferOwner(owner, bunch.id, key, request)).isEqualTo(result)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) {
            management.transferOwner(owner, bunch.id, UUID.randomUUID(), request)
        }
        assertThat(actionRequests.count()).isEqualTo(1)
    }

    @Test
    fun `owner transfer replay remains active just before the thirty day boundary`() {
        val owner = member()
        val bunch = create(owner)
        val target = joined(owner, bunch.id)
        val ownerMembership = membership(owner, bunch.id)
        val targetMembership = membership(target, bunch.id)
        val key = UUID.randomUUID()
        val request = OwnerTransferRequest(ownerMembership.id, version(bunch.id), targetMembership.id)
        val result = management.transferOwner(owner, bunch.id, key, request)
        val createdAt = testNow.minus(Duration.ofDays(30)).plus(1, ChronoUnit.MICROS)
        val expiresAt = testNow.plus(1, ChronoUnit.MICROS)

        jdbc.update(
            "UPDATE bunch_action_requests SET created_at = ?, expires_at = ? WHERE member_id = ? AND bunch_id = ? AND request_key = ?",
            Timestamp.from(createdAt),
            Timestamp.from(expiresAt),
            owner,
            bunch.id,
            key
        )

        assertThat(management.transferOwner(owner, bunch.id, key, request)).isEqualTo(result)
        assertThat(actionRequests.count()).isEqualTo(1)
    }

    @Test
    fun `owner transfer key at the exact thirty day boundary is expired before reuse`() {
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
                createdAt = testNow.minus(Duration.ofDays(30)),
                expiresAt = testNow
            )
        )

        management.transferOwner(
            owner,
            bunch.id,
            key,
            OwnerTransferRequest(ownerMembership.id, version(bunch.id), targetMembership.id)
        )

        assertThat(actionRequests.count()).isEqualTo(1)
        assertThat(actionRequests.findAll().single().expiresAt)
            .isEqualTo(testNow.plus(Duration.ofDays(30)))
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

    private fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty)
            .andExpect(jsonPath("$.error.code").value(error.code))
            .andExpect(jsonPath("$.error.message").value(error.messageKey))

    private data class Auth(val memberId: Long, val accessToken: String)
}
