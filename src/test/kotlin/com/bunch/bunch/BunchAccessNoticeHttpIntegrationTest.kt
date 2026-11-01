package com.bunch.bunch

import com.bunch.bunch.domain.BunchAccessNoticeRepository
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipStatus
import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BunchAccessNoticeHttpIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var accessNotices: BunchAccessNoticeService

    @Autowired
    private lateinit var notices: BunchAccessNoticeRepository

    @Autowired
    private lateinit var bunchesRepository: BunchRepository

    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @MockitoBean
    private lateinit var clock: Clock

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @BeforeEach
    fun setClock() {
        `when`(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"))
    }

    @AfterEach
    fun cleanSessions() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    @Test
    fun `notice routes enforce recipient ownership completion and deletion`() {
        val owner = member()
        val removed = member()
        val foreign = member()
        val incomplete = member(completed = false)
        val bunch = create(owner)
        join(removed, issue(owner, bunch.id))
        val membershipId = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunch.id)!!.id
        TransactionTemplate(transactionManager).execute {
            memberships.findById(membershipId).orElseThrow()
                .end(MembershipStatus.REMOVED, Instant.parse("2026-01-01T00:00:00Z"))
            memberships.flush()
        }
        val noticeId = TransactionTemplate(transactionManager).execute {
            val bunchEntity = bunchesRepository.findById(bunch.id).orElseThrow()
            val membership = memberships.findById(membershipId).orElseThrow()
            accessNotices.recordRemoval(
                bunchEntity,
                membership,
                Instant.parse("2026-01-01T00:00:00Z")
            ).id
        }!!

        getNotice(removed, noticeId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(noticeId))
            .andExpect(jsonPath("$.data.reason").value("REMOVED"))
        getNotice(foreign, noticeId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.RESOURCE_NOT_FOUND.code))
        getNotice(incomplete, noticeId)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value(ErrorCode.SIGNUP_INCOMPLETE.code))

        mvc.perform(
            delete("/api/v1/members/me/bunch-access-notices/{noticeId}", noticeId)
                .header(HttpHeaders.AUTHORIZATION, bearer(removed))
        ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isEmpty)
        assertThat(notices.findById(noticeId)).isEmpty
    }

    @Test
    fun `exit routes use the authenticated member and current membership version`() {
        val owner = member()
        val leaver = member()
        val removed = member()
        val bunch = create(owner)
        val invitation = issue(owner, bunch.id)
        join(leaver, invitation)
        join(removed, invitation)
        val leaverMembership = memberships.findByMemberIdAndBunchIdAndStatus(leaver, bunch.id)!!
        val ownerMembership = memberships.findByMemberIdAndBunchIdAndStatus(owner, bunch.id)!!
        val removedMembership = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunch.id)!!

        mvc.perform(
            post("/api/v1/bunches/{bunchId}/leave", bunch.id)
                .header(HttpHeaders.AUTHORIZATION, bearer(leaver))
                .header("Idempotency-Key", java.util.UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"actorMembershipId":${leaverMembership.id},"expectedMembershipVersion":${version(bunch.id)}}"""
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.departedMembershipId").value(leaverMembership.id))

        mvc.perform(
            post("/api/v1/bunches/{bunchId}/memberships/{membershipId}/remove", bunch.id, removedMembership.id)
                .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"actorMembershipId":${ownerMembership.id},"expectedMembershipVersion":${version(bunch.id)}}"""
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.removedMembershipId").value(removedMembership.id))
    }

    private fun getNotice(memberId: Long, noticeId: Long) = mvc.perform(
        get("/api/v1/members/me/bunch-access-notices/{noticeId}", noticeId)
            .header(HttpHeaders.AUTHORIZATION, bearer(memberId))
    )

    private fun version(bunchId: Long): Long = jdbc.queryForObject(
        "SELECT membership_version FROM bunches WHERE id = ?",
        Long::class.java,
        bunchId
    )!!

    private fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(
            sessionId,
            memberId,
            pair.refreshToken,
            tokens.getRefreshTokenValiditySeconds()
        )
        sessionIds += sessionId to memberId
        return "Bearer ${pair.accessToken}"
    }
}
