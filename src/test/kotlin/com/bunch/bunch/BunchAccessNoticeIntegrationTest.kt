package com.bunch.bunch

import com.bunch.bunch.domain.BunchAccessNoticeRepository
import com.bunch.bunch.domain.BunchAccessNoticeReason
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipStatus
import com.bunch.bunch.service.BunchAccessNoticeRetention
import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.bundle.service.BundleAccessService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.exception.BusinessException
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BunchAccessNoticeIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var accessNotices: BunchAccessNoticeService

    @Autowired
    private lateinit var noticeRetention: BunchAccessNoticeRetention

    @Autowired
    private lateinit var notices: BunchAccessNoticeRepository

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var bundleAccess: BundleAccessService

    @MockitoBean
    private lateinit var clock: Clock

    @org.junit.jupiter.api.BeforeEach
    fun setClock() {
        `when`(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"))
    }

    @Test
    fun `removal notice is private to a completed recipient and can be deleted`() {
        val owner = member()
        val removed = member()
        val foreign = member()
        val incomplete = member(completed = false)
        val bunchResponse = create(owner)
        val invitation = issue(owner, bunchResponse.id)
        join(removed, invitation)
        val removedMembershipId = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunchResponse.id)!!.id
        endMembership(removedMembershipId)
        val noticeId = recordRemoval(bunchResponse.id, removed)

        assertThat(accessNotices.get(removed, noticeId).reason)
            .isEqualTo(BunchAccessNoticeReason.REMOVED)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { accessNotices.get(foreign, noticeId) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { accessNotices.delete(foreign, noticeId) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { accessNotices.get(incomplete, noticeId) }

        accessNotices.delete(removed, noticeId)
        assertThat(notices.findById(noticeId)).isEmpty
    }

    @Test
    fun `rejoin creates a new membership and removes the ended membership notice`() {
        val owner = member()
        val visitor = member()
        val bunchResponse = create(owner)
        val invitation = issue(owner, bunchResponse.id)
        val oldKey = UUID.randomUUID()
        join(visitor, invitation, oldKey)
        val oldMembershipId = memberships.findByMemberIdAndBunchIdAndStatus(visitor, bunchResponse.id)!!.id
        endMembership(oldMembershipId)
        val noticeId = recordRemoval(bunchResponse.id, visitor)

        assertThat(accessNotices.active(visitor)).extracting<Long> { it.id }.containsExactly(noticeId)

        val rejoin = join(visitor, invitation, UUID.randomUUID())

        assertThat(rejoin.outcome.name).isEqualTo("JOINED")
        assertThat(memberships.findAllByBunchIdAndStatus(bunchResponse.id)).hasSize(2)
        assertThat(memberships.findById(oldMembershipId).orElseThrow().status)
            .isEqualTo(MembershipStatus.LEFT)
        assertThat(accessNotices.active(visitor)).isEmpty()
        assertThat(notices.findById(noticeId)).isEmpty
        assertError(ErrorCode.FORBIDDEN) { invitations.join(visitor, oldKey, invitation.url) }
    }

    @Test
    fun `active notices hide a stale row when a new membership is already active`() {
        val owner = member()
        val visitor = member()
        val bunchResponse = create(owner)
        val invitation = issue(owner, bunchResponse.id)
        join(visitor, invitation)
        val noticeId = recordRemoval(bunchResponse.id, visitor)

        assertThat(accessNotices.active(visitor)).isEmpty()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { accessNotices.get(visitor, noticeId) }
        assertThat(notices.findById(noticeId)).isPresent
    }

    @Test
    fun `deletion notice uses the same independent record contract`() {
        val owner = member()
        val removed = member()
        val bunchResponse = create(owner)
        join(removed, issue(owner, bunchResponse.id))
        val membershipId = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunchResponse.id)!!.id
        val noticeId = inTransaction {
            val bunch = bunchRepository.findById(bunchResponse.id).orElseThrow()
            val membership = memberships.findById(membershipId).orElseThrow()
            val notice = accessNotices.recordDeletion(
                bunch,
                membership,
                Instant.parse("2026-01-01T00:00:00Z")
            )
            membership.end(MembershipStatus.REMOVED, Instant.parse("2026-01-01T00:00:00Z"))
            memberships.flush()
            notice.id
        }

        assertThat(accessNotices.get(removed, noticeId).reason)
            .isEqualTo(BunchAccessNoticeReason.DELETED)
    }

    @Test
    fun `expired notices are hidden and physically removed`() {
        val owner = member()
        val removed = member()
        val bunchResponse = create(owner)
        join(removed, issue(owner, bunchResponse.id))
        val removedMembershipId = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunchResponse.id)!!.id
        endMembership(removedMembershipId)
        val noticeId = recordRemoval(
            bunchResponse.id,
            removed,
            Instant.parse("2025-12-01T00:00:00Z")
        )
        jdbc.update(
            "UPDATE bunch_access_notices SET expires_at = TIMESTAMP WITH TIME ZONE '2025-12-31 23:59:59Z' " +
                "WHERE id = ?",
            noticeId
        )

        assertError(ErrorCode.RESOURCE_NOT_FOUND) { accessNotices.get(removed, noticeId) }
        assertThat(noticeRetention.deleteExpired()).isEqualTo(1)
        assertThat(notices.findById(noticeId)).isEmpty
    }

    @Test
    fun `internal access reports an own active notice before membership forbidden`() {
        val owner = member()
        val removed = member()
        val outsider = member()
        val bunchResponse = create(owner)
        join(removed, issue(owner, bunchResponse.id))
        val membershipId = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunchResponse.id)!!.id
        endMembership(membershipId)
        val noticeId = recordRemoval(bunchResponse.id, removed)

        val unavailable = catchThrowable {
            accessNotices.requireMemberOrAccessNotice(removed, bunchResponse.id)
        }

        assertThat(unavailable).isInstanceOf(BusinessException::class.java)
        assertThat((unavailable as BusinessException).errorCode)
            .isEqualTo(ErrorCode.BUNCH_ACCESS_UNAVAILABLE)
        assertThat(unavailable.detail).isEqualTo(
            mapOf("noticeId" to noticeId, "reason" to BunchAccessNoticeReason.REMOVED.name)
        )
        assertError(ErrorCode.FORBIDDEN) {
            accessNotices.requireMemberOrAccessNotice(outsider, bunchResponse.id)
        }
    }

    @Test
    fun `deleted bunch still surfaces the recipient notice before lock not found`() {
        val owner = member()
        val removed = member()
        val bunchResponse = create(owner)
        join(removed, issue(owner, bunchResponse.id))
        val membershipId = memberships.findByMemberIdAndBunchIdAndStatus(removed, bunchResponse.id)!!.id
        endMembership(membershipId)
        val noticeId = recordRemoval(bunchResponse.id, removed)
        jdbc.update("DELETE FROM bunches WHERE id = ?", bunchResponse.id)

        val thrown = catchThrowable { bundleAccess.lockBunch(removed, bunchResponse.id) }

        assertThat(thrown).isInstanceOf(BusinessException::class.java)
        assertThat((thrown as BusinessException).errorCode)
            .isEqualTo(ErrorCode.BUNCH_ACCESS_UNAVAILABLE)
        assertThat(thrown.detail).isEqualTo(
            mapOf("noticeId" to noticeId, "reason" to BunchAccessNoticeReason.REMOVED.name)
        )
    }

    private fun recordRemoval(
        bunchId: Long,
        memberId: Long,
        occurredAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    ): Long = inTransaction {
        val bunch = bunchRepository.findById(bunchId).orElseThrow()
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(
            memberId,
            bunchId,
            MembershipStatus.ACTIVE
        ) ?: memberships.findAllByBunchIdAndStatus(bunchId, MembershipStatus.LEFT)
            .first { it.member.id == memberId }
        accessNotices.recordRemoval(
            bunch,
            membership,
            occurredAt
        ).id
    }

    private fun endMembership(membershipId: Long) = inTransaction {
        memberships.findById(membershipId).orElseThrow().end(MembershipStatus.LEFT, Instant.now())
        memberships.flush()
    }

    private fun <T> inTransaction(block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }!!
}
