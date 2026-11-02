package com.bunch.bunch

import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.domain.BunchFollowStatus
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.dto.CreateBunchRequest
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.dto.FollowDecision
import com.bunch.bunch.dto.FollowDecisionState
import com.bunch.bunch.dto.FollowDecisionResult
import com.bunch.bunch.dto.FollowState
import com.bunch.bunch.service.BunchSocialService
import com.bunch.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class BunchSocialIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var social: BunchSocialService

    @Autowired
    private lateinit var follows: BunchFollowRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `public follow is immediate and repeating it preserves its ID and actor`() {
        val owner = member()
        val viewer = create(owner).id
        val actor = member()
        join(actor, issue(owner, viewer))
        val target = publicBunch(member())

        val first = social.follow(actor, viewer, target)
        val replay = social.follow(owner, viewer, target)

        assertThat(first.followState).isEqualTo(FollowState.FOLLOWING)
        assertThat(replay).isEqualTo(first)
        val stored = follows.findById(first.followId!!).orElseThrow()
        assertThat(stored.createdByMemberId).isEqualTo(actor)
        assertThat(stored.approvedByMemberId).isNull()
        assertThat(stored.approvedAt).isEqualTo(stored.createdAt)
        assertThat(social.requests(owner, viewer, 0, 20).totalElements).isZero()
    }

    @Test
    fun `any active target member may approve and a subsequent rejection cannot undo approval`() {
        val actor = member()
        val viewer = create(actor).id
        val targetOwner = member()
        val target = create(targetOwner).id
        val approver = member()
        join(approver, issue(targetOwner, target))
        val pending = social.follow(actor, viewer, target)
        val pendingId = pending.followId!!

        assertThat(pending.followState).isEqualTo(FollowState.REQUESTED)
        assertThat(social.requests(approver, target, 0, 20).items.single().id).isEqualTo(pending.followId)
        val approved = social.decide(approver, target, pendingId, FollowDecision.APPROVE)
        assertThat(approved.applied).isTrue()
        assertThat(approved.state).isEqualTo(FollowDecisionState.APPROVED)
        val lateRejection = social.decide(targetOwner, target, pendingId, FollowDecision.REJECT)
        assertThat(lateRejection.applied).isFalse()
        assertThat(lateRejection.state).isEqualTo(FollowDecisionState.APPROVED)
        assertThat(follows.findById(pendingId).orElseThrow().approvedByMemberId).isEqualTo(approver)

        val cancelled = social.cancel(actor, viewer, target, pendingId)
        assertThat(cancelled.cancelled).isEqualTo(FollowState.FOLLOWING)
        assertThat(cancelled.followId).isNull()
    }

    @Test
    fun `rejection and cancellation delete only that request round`() {
        val actor = member()
        val viewer = create(actor).id
        val targetActor = member()
        val target = create(targetActor).id
        val oldId = social.follow(actor, viewer, target).followId!!
        assertThat(social.decide(targetActor, target, oldId, FollowDecision.REJECT).state)
            .isEqualTo(FollowDecisionState.REJECTED)
        val current = social.follow(actor, viewer, target)
        assertThat(current.followId).isNotEqualTo(oldId)
        listOf(FollowDecision.APPROVE, FollowDecision.REJECT).forEach {
            val old = social.decide(targetActor, target, oldId, it)
            assertThat(old.applied).isFalse()
            assertThat(old.state).isEqualTo(FollowDecisionState.UNAVAILABLE)
        }
        val oldCancel = social.cancel(actor, viewer, target, oldId)
        assertThat(oldCancel.cancelled).isEqualTo(FollowState.NONE)
        assertThat(oldCancel.followId).isEqualTo(current.followId)
        assertThat(oldCancel.followState).isEqualTo(FollowState.REQUESTED)
        assertThat(social.cancel(actor, viewer, target, current.followId!!).cancelled).isEqualTo(FollowState.REQUESTED)
        val next = social.follow(actor, viewer, target)
        assertThat(next.followId).isNotIn(oldId, current.followId)
    }

    @Test
    fun `same personal member may follow another own bunch while server current controls access`() {
        val actor = member()
        val viewer = create(actor).id
        val target = create(actor).id
        bunches.updateLastUsed(actor, viewer)
        assertError(ErrorCode.FORBIDDEN) { social.relations(actor, target, false, 0, 20) }
        val request = social.follow(actor, viewer, target)
        listOf(false, true).forEach { following ->
            assertError(ErrorCode.FORBIDDEN) { social.relations(actor, target, following, 0, 20) }
        }
        social.decide(actor, target, request.followId!!, FollowDecision.APPROVE)
        val followers = social.relations(actor, target, false, 0, 20)
        assertThat(followers.items.single().bunch.id).isEqualTo(viewer)
        assertThat(followers.items.single().followState).isEqualTo(FollowState.NONE)
        assertThat(social.relations(actor, target, true, 0, 20).items).isEmpty()
        social.cancel(actor, viewer, target, request.followId!!)
        listOf(false, true).forEach { following ->
            assertError(ErrorCode.FORBIDDEN) { social.relations(actor, target, following, 0, 20) }
        }
        assertError(ErrorCode.INVALID_INPUT) { social.follow(actor, viewer, viewer) }
    }

    @Test
    fun `relation pagination and state use selected viewer and stable time ID order`() {
        val actor = member()
        val target = publicBunch(actor)
        val viewer = create(actor).id
        val firstActor = member()
        val first = create(firstActor).id
        val secondActor = member()
        val second = create(secondActor).id
        val firstId = social.follow(firstActor, first, target).followId!!
        val secondId = social.follow(secondActor, second, target).followId!!
        val requestId = social.follow(actor, viewer, second).followId!!
        jdbc.update("UPDATE bunch_follows SET approved_at = '2026-01-01T00:00:00Z' WHERE id IN (?, ?)", firstId, secondId)

        val page = social.relations(actor, target, false, 0, 1)
        assertThat(page.totalElements).isEqualTo(2)
        assertThat(page.hasNext).isTrue()
        assertThat(page.items.single().bunch.id).isEqualTo(second)
        assertThat(page.items.single().followState).isEqualTo(FollowState.REQUESTED)
        assertThat(page.items.single().followId).isEqualTo(requestId)
        val last = social.relations(actor, target, false, 1, 1)
        assertThat(last.items.single().bunch.id).isEqualTo(first)
        assertThat(last.items.single().followState).isEqualTo(FollowState.NONE)
        assertThat(last.hasNext).isFalse()
        assertThat(social.relations(firstActor, first, true, 0, 20).items.single().bunch.id).isEqualTo(target)
        val zeroMembershipActor = member()
        assertThat(social.relations(zeroMembershipActor, target, false, 0, 20).totalElements).isEqualTo(2)
    }

    @Test
    fun `pending request pages exclude approved rows and break timestamp ties by request ID`() {
        val targetActor = member()
        val target = create(targetActor).id
        val requestIds = (1..4).map {
            val actor = member()
            social.follow(actor, create(actor).id, target).followId!!
        }
        social.decide(targetActor, target, requestIds[2], FollowDecision.APPROVE)
        jdbc.update(
            "UPDATE bunch_follows SET created_at = '2026-01-01T00:00:00Z' WHERE followed_bunch_id = ?",
            target
        )

        val first = social.requests(targetActor, target, 0, 2)
        assertThat(first.items.map { it.id }).containsExactly(requestIds[3], requestIds[1])
        assertThat(first.totalElements).isEqualTo(3)
        assertThat(first.hasNext).isTrue()
        val last = social.requests(targetActor, target, 1, 2)
        assertThat(last.items.map { it.id }).containsExactly(requestIds[0])
        assertThat(last.totalElements).isEqualTo(3)
        assertThat(last.hasNext).isFalse()
        assertThat(social.relations(targetActor, target, false, 0, 20).totalElements).isEqualTo(1)
    }

    @Test
    fun `storage failures cannot create approve or cancel a relationship partially`() {
        val actor = member()
        val viewer = create(actor).id
        val targetActor = member()
        val target = create(targetActor).id
        val id = social.follow(actor, viewer, target).followId!!
        val secondActor = member()
        val secondViewer = create(secondActor).id
        jdbc.execute(
            """CREATE FUNCTION bunch_social_reject_write() RETURNS trigger LANGUAGE plpgsql AS
                'BEGIN RAISE EXCEPTION ''injected relationship write failure''; END'"""
        )
        try {
            jdbc.execute(
                """CREATE TRIGGER bunch_social_reject_write BEFORE INSERT OR UPDATE OR DELETE ON bunch_follows
                    FOR EACH ROW EXECUTE FUNCTION bunch_social_reject_write()"""
            )
            assertThatThrownBy { social.follow(secondActor, secondViewer, target) }
                .isInstanceOf(DataAccessException::class.java)
            assertThat(follows.findByFollowerBunchIdAndFollowedBunchId(secondViewer, target)).isNull()
            assertThatThrownBy { social.decide(targetActor, target, id, FollowDecision.APPROVE) }
                .isInstanceOf(DataAccessException::class.java)
            val pending = follows.findById(id).orElseThrow()
            assertThat(pending.status).isEqualTo(BunchFollowStatus.PENDING)
            assertThat(pending.approvedByMemberId).isNull()
            assertThat(pending.approvedAt).isNull()
            assertThatThrownBy { social.cancel(actor, viewer, target, id) }
                .isInstanceOf(DataAccessException::class.java)
            assertThat(social.requests(targetActor, target, 0, 20).items.map { it.id }).containsExactly(id)
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS bunch_social_reject_write ON bunch_follows")
            jdbc.execute("DROP FUNCTION bunch_social_reject_write()")
        }
        assertThat(social.decide(targetActor, target, id, FollowDecision.APPROVE).applied).isTrue()
        assertThat(social.cancel(actor, viewer, target, id).cancelled).isEqualTo(FollowState.FOLLOWING)
    }

    @Test
    fun `real follow and approval join the caller transaction and roll back together`() {
        val actor = member()
        val viewer = create(actor).id
        val targetActor = member()
        val target = create(targetActor).id
        val tx = TransactionTemplate(transactionManager)
        assertThatThrownBy {
            tx.executeWithoutResult {
                social.follow(actor, viewer, target)
                error("rollback caller")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(follows.findByFollowerBunchIdAndFollowedBunchId(viewer, target)).isNull()
        val id = social.follow(actor, viewer, target).followId!!
        assertThatThrownBy {
            tx.executeWithoutResult {
                social.decide(targetActor, target, id, FollowDecision.APPROVE)
                error("rollback caller")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(social.requests(targetActor, target, 0, 20).items.map { it.id }).containsExactly(id)
        assertThat(follows.findById(id).orElseThrow().approvedAt).isNull()
    }

    @Test
    fun `inactive memberships unfinished accounts and foreign requests cannot act`() {
        val actor = member()
        val viewer = create(actor).id
        val targetActor = member()
        val target = create(targetActor).id
        val requestId = social.follow(actor, viewer, target).followId!!
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { social.decide(actor, viewer, requestId, FollowDecision.APPROVE) }
        val outsider = member()
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { social.follow(outsider, viewer, target) }
        create(outsider)
        assertError(ErrorCode.FORBIDDEN) { social.follow(outsider, viewer, target) }
        val unfinished = member(false)
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { social.follow(unfinished, viewer, target) }
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ?", viewer)
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { social.cancel(actor, viewer, target, requestId) }
        assertThat(follows.existsById(requestId)).isTrue()
    }

    @Test
    fun `concurrent duplicate requests from different members create one relationship`() {
        val actor = member()
        val viewer = create(actor).id
        val otherActor = member()
        join(otherActor, issue(actor, viewer))
        val target = create(member()).id
        val results = concurrently(listOf(
            { social.follow(actor, viewer, target) },
            { social.follow(otherActor, viewer, target) }
        )).map { it.getOrThrow() }
        assertThat(results.map { it.followId }.distinct()).hasSize(1)
        assertThat(count("bunch_follows")).isEqualTo(1)
    }

    @Test
    fun `concurrent reports by different active members preserve one original report`() {
        val owner = member()
        val viewer = create(owner).id
        val participant = member()
        join(participant, issue(owner, viewer))
        val target = publicBunch(member())
        val results = concurrently(listOf(
            { social.report(owner, viewer, BunchReportRequest(target, BunchReportReason.SPAM, "first")) },
            { social.report(participant, viewer, BunchReportRequest(target, BunchReportReason.OTHER, "second")) }
        )).map { it.getOrThrow() }
        assertThat(results.map { it.reportId }.distinct()).hasSize(1)
        assertThat(count("bunch_profile_reports")).isEqualTo(1)
    }

    @Test
    fun `opposite direction requests use one consistent pair lock order`() {
        val firstActor = member()
        val first = publicBunch(firstActor)
        val secondActor = member()
        val second = publicBunch(secondActor)
        val results = concurrently(listOf(
            { social.follow(firstActor, first, second) },
            { social.follow(secondActor, second, first) }
        )).map { it.getOrThrow() }
        assertThat(results.map { it.followState }).containsOnly(FollowState.FOLLOWING)
        assertThat(count("bunch_follows")).isEqualTo(2)
    }

    @Test
    fun `concurrent approval and cancellation always leave no relationship`() {
        val actor = member()
        val viewer = create(actor).id
        val targetActor = member()
        val target = create(targetActor).id
        repeat(6) {
            val id = social.follow(actor, viewer, target).followId!!
            concurrently(listOf(
                { social.decide(targetActor, target, id, FollowDecision.APPROVE) },
                { social.cancel(actor, viewer, target, id) }
            )).forEach { it.getOrThrow() }
            assertThat(follows.findByFollowerBunchIdAndFollowedBunchId(viewer, target)).isNull()
        }
    }

    @Test
    fun `concurrent first approval or rejection wins and late decision is a no-op`() {
        val actor = member()
        val viewer = create(actor).id
        val targetActor = member()
        val target = create(targetActor).id
        val secondApprover = member()
        join(secondApprover, issue(targetActor, target))
        repeat(6) {
            val id = social.follow(actor, viewer, target).followId!!
            val results = concurrently(listOf(
                { social.decide(targetActor, target, id, FollowDecision.APPROVE) },
                { social.decide(secondApprover, target, id, FollowDecision.REJECT) }
            )).map { it.getOrThrow() }
            assertThat(results.count { it.applied }).isEqualTo(1)
            val winner = results.single { it.applied }
            assertThat(follows.existsById(id)).isEqualTo(winner.state == FollowDecisionState.APPROVED)
            social.cancel(actor, viewer, target, id)
        }
    }

    @Test
    fun `decision blocked after reading old coordinates cannot approve a replacement request`() {
        val actor = member()
        val viewer = create(actor).id
        val targetActor = member()
        val target = create(targetActor).id
        val oldId = social.follow(actor, viewer, target).followId!!
        val executor = Executors.newSingleThreadExecutor()
        lateinit var decision: Future<FollowDecisionResult>
        try {
            val newId = TransactionTemplate(transactionManager).execute {
                jdbc.queryForObject("SELECT id FROM members WHERE id = ? FOR UPDATE", Long::class.java, actor)
                jdbc.queryForList("SELECT id FROM bunches WHERE id IN (?, ?) ORDER BY id FOR UPDATE", viewer, target)
                val holderPid = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
                decision = executor.submit<FollowDecisionResult> {
                    social.decide(targetActor, target, oldId, FollowDecision.APPROVE)
                }
                awaitBlockedBy(holderPid)
                social.cancel(actor, viewer, target, oldId)
                social.follow(actor, viewer, target).followId!!
            }!!
            val result = decision.get(10, TimeUnit.SECONDS)
            assertThat(result.applied).isFalse()
            assertThat(result.state).isEqualTo(FollowDecisionState.UNAVAILABLE)
            assertThat(newId).isNotEqualTo(oldId)
            assertThat(social.requests(targetActor, target, 0, 20).items.map { it.id }).containsExactly(newId)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `deleting a requester makes its pending decision unavailable and removes its relationships`() {
        val actor = member()
        val viewer = create(actor).id
        val targetActor = member()
        val target = create(targetActor).id
        val id = social.follow(actor, viewer, target).followId!!
        jdbc.update("DELETE FROM bunches WHERE id = ?", viewer)
        val result = social.decide(targetActor, target, id, FollowDecision.APPROVE)
        assertThat(result.applied).isFalse()
        assertThat(result.state).isEqualTo(FollowDecisionState.UNAVAILABLE)
        assertThat(count("bunch_follows")).isZero()
    }

    private fun awaitBlockedBy(holderPid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val blocked = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                Boolean::class.java,
                holderPid
            )!!
            if (blocked) return
            Thread.sleep(20)
        }
        error("decision did not reach the held bunch lock")
    }

    private fun publicBunch(actor: Long): Long = bunches.create(
        actor,
        UUID.randomUUID(),
        CreateBunchRequest(uniqueName(), "공개 번치", Visibility.PUBLIC, CharacterType.TYPE_2)
    ).id
}
