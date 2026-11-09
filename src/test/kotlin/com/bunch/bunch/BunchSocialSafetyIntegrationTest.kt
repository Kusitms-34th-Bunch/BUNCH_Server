package com.bunch.bunch

import com.bunch.bunch.domain.BunchBlockRepository
import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.domain.BunchFollowStatus
import com.bunch.bunch.domain.BunchProfileReportRepository
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.dto.CreateBunchRequest
import com.bunch.bunch.dto.FollowCancellationResult
import com.bunch.bunch.dto.FollowDecision
import com.bunch.bunch.dto.FollowDecisionResult
import com.bunch.bunch.dto.FollowDecisionState
import com.bunch.bunch.dto.FollowState
import com.bunch.bunch.service.BunchSocialService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.service.NotificationService
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
class BunchSocialSafetyIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var social: BunchSocialService

    @Autowired
    private lateinit var follows: BunchFollowRepository

    @Autowired
    private lateinit var blocks: BunchBlockRepository

    @Autowired
    private lateinit var reports: BunchProfileReportRepository

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var notificationService: NotificationService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `only owner and co-owner may block while every active member may list blocks`() {
        val owner = member()
        val viewer = publicBunch(owner)
        val coOwner = member()
        join(coOwner, issue(owner, viewer))
        jdbc.update(
            "UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE bunch_id = ? AND member_id = ?",
            viewer,
            coOwner
        )
        val activeMember = member()
        join(activeMember, issue(owner, viewer))
        val inactiveMember = member()
        join(inactiveMember, issue(owner, viewer))
        publicBunch(inactiveMember)
        val target = publicBunch(member())

        assertError(ErrorCode.FORBIDDEN) { social.block(activeMember, viewer, target) }

        social.block(coOwner, viewer, target)
        val listed = social.blocks(activeMember, viewer, 0, 20)
        assertThat(listed.items).hasSize(1)
        assertThat(listed.items.single().bunch.id).isEqualTo(target)
        assertThat(listed.items.single().id).isPositive()
        assertThat(listed.totalElements).isEqualTo(1)
        assertThat(listed.hasNext).isFalse()

        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = now() WHERE bunch_id = ? AND member_id = ?",
            viewer,
            inactiveMember
        )
        assertError(ErrorCode.FORBIDDEN) { social.blocks(inactiveMember, viewer, 0, 20) }

        social.unblock(coOwner, viewer, target, listed.items.single().id)
        assertThat(social.blocks(owner, viewer, 0, 20).items).isEmpty()
    }

    @Test
    fun `blocking removes both follow directions and stale unblock never restores them`() {
        val firstOwner = member()
        val first = publicBunch(firstOwner)
        val secondOwner = member()
        val second = publicBunch(secondOwner)

        val firstToSecond = social.follow(firstOwner, first, second).followId!!
        val secondToFirst = social.follow(secondOwner, second, first).followId!!
        assertThat(follows.findById(firstToSecond).orElseThrow().status)
            .isEqualTo(BunchFollowStatus.APPROVED)
        assertThat(follows.findById(secondToFirst).orElseThrow().status)
            .isEqualTo(BunchFollowStatus.APPROVED)

        social.block(firstOwner, first, second)
        val firstBlockId = social.blocks(firstOwner, first, 0, 20).items.single().id

        assertThat(follows.findByFollowerBunchIdAndFollowedBunchId(first, second)).isNull()
        assertThat(follows.findByFollowerBunchIdAndFollowedBunchId(second, first)).isNull()
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) {
            social.relations(firstOwner, second, false, 0, 20)
        }
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) {
            social.relations(secondOwner, first, false, 0, 20)
        }
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { social.follow(secondOwner, second, first) }

        social.block(secondOwner, second, first)
        val oppositeBlockId = social.blocks(secondOwner, second, 0, 20).items.single().id
        assertThat(blocks.blocked(first, second)).isTrue()

        social.unblock(firstOwner, first, second, firstBlockId + 10_000)
        assertThat(social.blocks(firstOwner, first, 0, 20).items.map { it.id })
            .containsExactly(firstBlockId)
        assertThat(blocks.blocked(first, second)).isTrue()

        social.unblock(firstOwner, first, second, firstBlockId)
        assertThat(social.blocks(firstOwner, first, 0, 20).items).isEmpty()
        assertThat(social.blocks(secondOwner, second, 0, 20).items.map { it.id })
            .containsExactly(oppositeBlockId)
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) {
            social.relations(firstOwner, second, false, 0, 20)
        }

        social.unblock(secondOwner, second, first, oppositeBlockId)
        assertThat(follows.findByFollowerBunchIdAndFollowedBunchId(first, second)).isNull()
        assertThat(follows.findByFollowerBunchIdAndFollowedBunchId(second, first)).isNull()
        assertThat(social.relations(firstOwner, second, false, 0, 20).items).isEmpty()
        assertThat(social.relations(secondOwner, first, false, 0, 20).items).isEmpty()
    }

    @Test
    fun `repeating a block keeps its ID and an old ID cannot remove a newer block`() {
        val owner = member()
        val viewer = publicBunch(owner)
        val target = publicBunch(member())

        social.block(owner, viewer, target)
        val firstId = social.blocks(owner, viewer, 0, 20).items.single().id
        social.block(owner, viewer, target)
        assertThat(social.blocks(owner, viewer, 0, 20).items.map { it.id })
            .containsExactly(firstId)

        social.unblock(owner, viewer, target, firstId)
        social.block(owner, viewer, target)
        val secondId = social.blocks(owner, viewer, 0, 20).items.single().id
        assertThat(secondId).isNotEqualTo(firstId)

        social.unblock(owner, viewer, target, firstId)
        assertThat(social.blocks(owner, viewer, 0, 20).items.map { it.id })
            .containsExactly(secondId)
        social.unblock(owner, viewer, target, secondId)
    }

    @Test
    fun `self actions are invalid but the same member may report and block another own bunch`() {
        val owner = member()
        val viewer = create(owner).id
        val reportTarget = create(owner).id
        val blockTarget = create(owner).id

        val report = social.report(
            owner,
            viewer,
            BunchReportRequest(reportTarget, BunchReportReason.SPAM)
        )
        social.block(owner, viewer, blockTarget)

        assertThat(report.reportId).isPositive()
        assertThat(social.blocks(owner, viewer, 0, 20).items.map { it.bunch.id })
            .containsExactly(blockTarget)
        assertError(ErrorCode.INVALID_INPUT) { social.block(owner, viewer, viewer) }
        assertError(ErrorCode.INVALID_INPUT) {
            social.report(owner, viewer, BunchReportRequest(viewer, BunchReportReason.SPAM))
        }
        assertError(ErrorCode.INVALID_INPUT) {
            social.unblock(owner, viewer, viewer, 1)
        }
    }

    @Test
    fun `report accepts active members, normalizes detail, and enforces the bunch quota`() {
        val owner = member()
        val viewer = create(owner).id
        val reporter = member()
        join(reporter, issue(owner, viewer))
        val target = publicBunch(member())

        assertError(ErrorCode.INVALID_INPUT) {
            social.report(
                reporter,
                viewer,
                BunchReportRequest(target, BunchReportReason.OTHER, "   ")
            )
        }
        assertError(ErrorCode.INVALID_INPUT) {
            social.report(
                reporter,
                viewer,
                BunchReportRequest(target, BunchReportReason.OTHER, "🙂".repeat(201))
            )
        }

        val first = social.report(
            reporter,
            viewer,
            BunchReportRequest(target, BunchReportReason.OTHER, "  first detail  ")
        )
        val stored = reports.findById(first.reportId).orElseThrow()
        assertThat(stored.reportedByMemberId).isEqualTo(reporter)
        assertThat(stored.reason).isEqualTo(BunchReportReason.OTHER)
        assertThat(stored.detail).isEqualTo("first detail")
        assertThat(stored.status).isEqualTo("RECEIVED")

        val second = social.report(
            owner,
            viewer,
            BunchReportRequest(target, BunchReportReason.SPAM, "  replacement  ")
        )
        assertThat(second.reportId).isNotEqualTo(first.reportId)
        val secondStored = reports.findById(second.reportId).orElseThrow()
        assertThat(secondStored.reportedByMemberId).isEqualTo(owner)
        assertThat(secondStored.reason).isEqualTo(BunchReportReason.SPAM)
        assertThat(secondStored.detail).isEqualTo("replacement")
        assertThat(secondStored.status).isEqualTo("RECEIVED")
        assertThat(reportCount(viewer, target)).isEqualTo(2)

        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) {
            social.report(owner, viewer, BunchReportRequest(target, BunchReportReason.SPAM, "third"))
        }

        assertError(ErrorCode.INVALID_INPUT) {
            social.report(
                owner,
                viewer,
                BunchReportRequest(target, BunchReportReason.OTHER, "🙂".repeat(201))
            )
        }

        val secondViewer = create(owner).id
        val otherBunchReport = social.report(
            owner,
            secondViewer,
            BunchReportRequest(target, BunchReportReason.SPAM, "  ")
        )
        assertThat(reports.findById(otherBunchReport.reportId).orElseThrow().detail).isNull()
    }

    @Test
    fun `block and report storage failures roll back their actual PostgreSQL writes`() {
        val firstOwner = member()
        val first = publicBunch(firstOwner)
        val second = publicBunch(member())
        val followId = social.follow(firstOwner, first, second).followId!!

        rejectWrites("bunch_blocks", "INSERT OR DELETE") {
            assertThatThrownBy { social.block(firstOwner, first, second) }
                .isInstanceOf(DataAccessException::class.java)
        }
        assertThat(blocks.findByBlockerBunchIdAndBlockedBunchId(first, second)).isNull()
        assertThat(follows.findById(followId)).isPresent

        val transaction = TransactionTemplate(transactionManager)
        assertThatThrownBy {
            transaction.executeWithoutResult {
                social.block(firstOwner, first, second)
                error("caller rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(blocks.findByBlockerBunchIdAndBlockedBunchId(first, second)).isNull()
        assertThat(follows.findById(followId)).isPresent

        val reportOwner = member()
        val reportViewer = create(reportOwner).id
        val reportTarget = publicBunch(member())
        val reportRequest = BunchReportRequest(reportTarget, BunchReportReason.SPAM)
        rejectWrites("bunch_profile_reports", "INSERT") {
            assertThatThrownBy { social.report(reportOwner, reportViewer, reportRequest) }
                .isInstanceOf(DataAccessException::class.java)
        }
        assertThat(reportCount(reportViewer, reportTarget)).isZero()

        assertThatThrownBy {
            transaction.executeWithoutResult {
                social.report(reportOwner, reportViewer, reportRequest)
                error("caller rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(reportCount(reportViewer, reportTarget)).isZero()
    }

    @Test
    fun `a blocked request is serialized against follow without resurrecting a relationship`() {
        val firstOwner = member()
        val first = publicBunch(firstOwner)
        val secondOwner = member()
        val second = publicBunch(secondOwner)

        val results = concurrently(
            listOf(
                { social.block(firstOwner, first, second) },
                { social.follow(secondOwner, second, first) }
            )
        )
        results[0].getOrThrow()
        assertExpectedBlockVisibilityFailure(results[1])
        assertThat(blocks.findByBlockerBunchIdAndBlockedBunchId(first, second)).isNotNull
        assertThat(follows.findByFollowerBunchIdAndFollowedBunchId(second, first)).isNull()
    }

    @Test
    fun `a blocked request is serialized against approval without resurrecting a relationship`() {
        val requester = member()
        val requesterBunch = create(requester).id
        val targetOwner = member()
        val target = create(targetOwner).id
        val requestId = social.follow(requester, requesterBunch, target).followId!!

        val results = concurrently(
            listOf(
                { social.block(targetOwner, target, requesterBunch) },
                { social.decide(targetOwner, target, requestId, FollowDecision.APPROVE) }
            )
        )
        results[0].getOrThrow()
        val decision = results[1].getOrThrow() as FollowDecisionResult
        assertThat(decision.state).isIn(FollowDecisionState.APPROVED, FollowDecisionState.UNAVAILABLE)
        assertThat(blocks.findByBlockerBunchIdAndBlockedBunchId(target, requesterBunch)).isNotNull
        assertThat(follows.findById(requestId)).isEmpty
    }

    @Test
    fun `a blocked request is serialized against cancellation without resurrecting a relationship`() {
        val requester = member()
        val requesterBunch = create(requester).id
        val targetOwner = member()
        val target = create(targetOwner).id
        val requestId = social.follow(requester, requesterBunch, target).followId!!

        val results = concurrently(
            listOf(
                { social.block(targetOwner, target, requesterBunch) },
                { social.cancel(requester, requesterBunch, target, requestId) }
            )
        )
        results[0].getOrThrow()
        val cancellation = results[1].getOrThrow() as FollowCancellationResult
        assertThat(cancellation.followState).isEqualTo(FollowState.NONE)
        assertThat(cancellation.followId).isNull()
        assertThat(cancellation.cancelled).isIn(FollowState.REQUESTED, FollowState.NONE)
        assertThat(blocks.findByBlockerBunchIdAndBlockedBunchId(target, requesterBunch)).isNotNull
        assertThat(follows.findById(requestId)).isEmpty
    }

    @Test
    fun `blocks reject reports before quota and unblocking preserves consumed quota`() {
        val reporter = member()
        val reporterBunch = publicBunch(reporter)
        val targetOwner = member()
        val target = publicBunch(targetOwner)
        val first = social.report(
            reporter,
            reporterBunch,
            BunchReportRequest(target, BunchReportReason.OTHER, "  original detail  ")
        )
        val second = social.report(
            reporter,
            reporterBunch,
            BunchReportRequest(target, BunchReportReason.SPAM, "replacement")
        )
        assertThat(second.reportId).isNotEqualTo(first.reportId)
        assertThat(reports.findById(first.reportId).orElseThrow().detail).isEqualTo("original detail")
        assertThat(reports.findById(second.reportId).orElseThrow().detail).isEqualTo("replacement")

        social.block(reporter, reporterBunch, target)
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) {
            social.report(reporter, reporterBunch, BunchReportRequest(target, BunchReportReason.SPAM))
        }

        val reporterBlockId = social.blocks(reporter, reporterBunch, 0, 20).items.single().id
        social.unblock(reporter, reporterBunch, target, reporterBlockId)
        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) {
            social.report(reporter, reporterBunch, BunchReportRequest(target, BunchReportReason.SPAM))
        }

        social.block(targetOwner, target, reporterBunch)
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) {
            social.report(reporter, reporterBunch, BunchReportRequest(target, BunchReportReason.SPAM))
        }
        val targetBlockId = social.blocks(targetOwner, target, 0, 20).items.single().id
        social.unblock(targetOwner, target, reporterBunch, targetBlockId)
        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) {
            social.report(reporter, reporterBunch, BunchReportRequest(target, BunchReportReason.SPAM))
        }
    }

    @Test
    fun `a waiting block rechecks a downgraded co-owner role after the pair lock`() {
        val owner = member()
        val viewer = publicBunch(owner)
        val coOwner = member()
        join(coOwner, issue(owner, viewer))
        jdbc.update(
            "UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE bunch_id = ? AND member_id = ?",
            viewer,
            coOwner
        )
        val target = publicBunch(member())
        val executor = Executors.newSingleThreadExecutor()

        try {
            lateinit var blocked: Future<Result<Unit>>
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.queryForList(
                    "SELECT id FROM bunches WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                    viewer,
                    target
                )
                val holderPid = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
                blocked = executor.submit<Result<Unit>> {
                    runCatching { social.block(coOwner, viewer, target) }
                }
                awaitBlockedBy(holderPid)
                jdbc.update(
                    "UPDATE bunch_memberships SET role = 'MEMBER' WHERE bunch_id = ? AND member_id = ?",
                    viewer,
                    coOwner
                )
            }

            val outcome = blocked.get(10, TimeUnit.SECONDS)
            assertThat(outcome.isFailure).isTrue()
            val error = outcome.exceptionOrNull()
            assertThat(error).isInstanceOf(BusinessException::class.java)
            assertThat((error as BusinessException).errorCode).isEqualTo(ErrorCode.FORBIDDEN)
            assertThat(blocks.findByBlockerBunchIdAndBlockedBunchId(viewer, target)).isNull()
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `follow notifications use exact snapshots, active recipients, and one public event`() {
        val actor = member()
        val follower = publicBunch(actor)
        val followerMember = member()
        join(followerMember, issue(actor, follower))
        val targetOwner = member()
        val target = publicBunch(targetOwner)
        val followerName = bunchName(follower)
        val targetMember = member()
        join(targetMember, issue(targetOwner, target))
        join(actor, issue(targetOwner, target))
        val inactiveTargetMember = member()
        join(inactiveTargetMember, issue(targetOwner, target))
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = now() WHERE bunch_id = ? AND member_id = ?",
            target,
            inactiveTargetMember
        )

        val result = social.follow(actor, follower, target)
        social.follow(followerMember, follower, target)

        assertThat(result.followState).isEqualTo(FollowState.FOLLOWING)
        val rows = socialNotifications(target, NotificationType.BUNCH_FOLLOWED)
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.recipientMemberId }).containsExactlyInAnyOrder(targetOwner, targetMember)
        assertThat(rows.map { it.recipientMemberId })
            .doesNotContain(actor, inactiveTargetMember)
        rows.forEach { notification ->
            assertThat(notification.title).isEqualTo("$followerName 번치가 우리 번치를 팔로우했어요")
            assertThat(notification.body).isEqualTo("'$followerName' 번치의 기록도 구경해 보세요.")
            assertThat(notification.dedupeKey).isEqualTo("BUNCH_FOLLOWED:${result.followId}")
            assertThat(notification.target.kind).isEqualTo(NotificationTargetKind.OTHER_BUNCH_PROFILE)
            assertThat(notification.target.bunchId).isEqualTo(target)
            assertThat(notification.target.targetBunchId).isEqualTo(follower)
        }
    }

    @Test
    fun `pending request rounds and approval notifications are exact and replay safe`() {
        val requester = member()
        val follower = create(requester).id
        val followerMember = member()
        join(followerMember, issue(requester, follower))
        val targetOwner = member()
        val target = create(targetOwner).id
        val followerName = bunchName(follower)
        val targetName = bunchName(target)
        val targetMember = member()
        join(targetMember, issue(targetOwner, target))
        join(requester, issue(targetOwner, target))
        join(targetOwner, issue(requester, follower))
        val inactiveTargetMember = member()
        join(inactiveTargetMember, issue(targetOwner, target))
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = now() WHERE bunch_id = ? AND member_id = ?",
            target,
            inactiveTargetMember
        )
        val inactiveFollowerMember = member()
        join(inactiveFollowerMember, issue(requester, follower))
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = now() WHERE bunch_id = ? AND member_id = ?",
            follower,
            inactiveFollowerMember
        )

        val first = social.follow(requester, follower, target)
        val firstId = first.followId!!
        assertThat(social.cancel(requester, follower, target, firstId).cancelled)
            .isEqualTo(FollowState.REQUESTED)
        val second = social.follow(requester, follower, target)
        val secondId = second.followId!!
        assertThat(secondId).isNotEqualTo(firstId)

        val pendingRows = socialNotifications(target, NotificationType.FOLLOW_REQUESTED)
        assertThat(pendingRows).hasSize(4)
        assertThat(pendingRows.map { it.recipientMemberId })
            .containsExactlyInAnyOrder(targetOwner, targetMember, targetOwner, targetMember)
        assertThat(pendingRows.map { it.recipientMemberId })
            .doesNotContain(requester, inactiveTargetMember)
        pendingRows.forEach { notification ->
            assertThat(notification.title).isEqualTo("$followerName 번치가 팔로우를 요청했어요")
            assertThat(notification.body).isEqualTo("요청을 확인하고 승인해 보세요.")
            assertThat(notification.dedupeKey)
                .isIn("FOLLOW_REQUESTED:$firstId", "FOLLOW_REQUESTED:$secondId")
            assertThat(notification.target.kind).isEqualTo(NotificationTargetKind.OTHER_BUNCH_PROFILE)
            assertThat(notification.target.bunchId).isEqualTo(target)
            assertThat(notification.target.targetBunchId).isEqualTo(follower)
        }

        val stale = social.decide(targetOwner, target, firstId, FollowDecision.APPROVE)
        assertThat(stale.applied).isFalse()
        assertThat(stale.state).isEqualTo(FollowDecisionState.UNAVAILABLE)
        assertThat(socialNotifications(follower, NotificationType.FOLLOW_REQUEST_APPROVED)).isEmpty()

        val approved = social.decide(targetOwner, target, secondId, FollowDecision.APPROVE)
        assertThat(approved.applied).isTrue()
        val targetFollowed = socialNotifications(target, NotificationType.BUNCH_FOLLOWED)
        assertThat(targetFollowed).hasSize(2)
        assertThat(targetFollowed.map { it.recipientMemberId })
            .containsExactlyInAnyOrder(requester, targetMember)
        assertThat(targetFollowed.map { it.recipientMemberId })
            .doesNotContain(targetOwner, inactiveTargetMember)
        targetFollowed.forEach { notification ->
            assertThat(notification.title).isEqualTo("$followerName 번치가 우리 번치를 팔로우했어요")
            assertThat(notification.body).isEqualTo("'$followerName' 번치의 기록도 구경해 보세요.")
            assertThat(notification.dedupeKey).isEqualTo("BUNCH_FOLLOWED:$secondId")
        }

        val approvedRows = socialNotifications(follower, NotificationType.FOLLOW_REQUEST_APPROVED)
        assertThat(approvedRows).hasSize(2)
        assertThat(approvedRows.map { it.recipientMemberId })
            .containsExactlyInAnyOrder(requester, followerMember)
        assertThat(approvedRows.map { it.recipientMemberId })
            .doesNotContain(targetOwner, inactiveFollowerMember)
        approvedRows.forEach { notification ->
            assertThat(notification.title).isEqualTo("$targetName 번치가 팔로우 요청을 승인했어요")
            assertThat(notification.body).isEqualTo("이제 '$targetName' 번치의 새로운 기록을 만나볼 수 있어요.")
            assertThat(notification.dedupeKey).isEqualTo("FOLLOW_REQUEST_APPROVED:$secondId")
            assertThat(notification.target.kind).isEqualTo(NotificationTargetKind.OTHER_BUNCH_PROFILE)
            assertThat(notification.target.bunchId).isEqualTo(follower)
            assertThat(notification.target.targetBunchId).isEqualTo(target)
        }

        social.decide(targetOwner, target, secondId, FollowDecision.REJECT)
        social.follow(followerMember, follower, target)
        assertThat(socialNotifications(target, NotificationType.BUNCH_FOLLOWED)).hasSize(2)
        assertThat(socialNotifications(follower, NotificationType.FOLLOW_REQUEST_APPROVED)).hasSize(2)
    }

    @Test
    fun `notification insert failure rolls back the follow and approval transaction`() {
        val requester = member()
        val follower = create(requester).id
        val targetOwner = member()
        val target = create(targetOwner).id

        rejectWrites("notifications", "INSERT") {
            assertThatThrownBy { social.follow(requester, follower, target) }
                .isInstanceOf(DataAccessException::class.java)
        }
        assertThat(follows.findByFollowerBunchIdAndFollowedBunchId(follower, target)).isNull()
        assertThat(socialNotifications(target, NotificationType.FOLLOW_REQUESTED)).isEmpty()

        val requestId = social.follow(requester, follower, target).followId!!
        val before = socialNotifications(target, NotificationType.FOLLOW_REQUESTED).size
        rejectWrites("notifications", "INSERT") {
            assertThatThrownBy {
                social.decide(targetOwner, target, requestId, FollowDecision.APPROVE)
            }.isInstanceOf(DataAccessException::class.java)
        }
        val stored = follows.findById(requestId).orElseThrow()
        assertThat(stored.status).isEqualTo(BunchFollowStatus.PENDING)
        assertThat(stored.approvedAt).isNull()
        assertThat(socialNotifications(target, NotificationType.FOLLOW_REQUESTED)).hasSize(before)
        assertThat(socialNotifications(target, NotificationType.BUNCH_FOLLOWED)).isEmpty()
        assertThat(socialNotifications(follower, NotificationType.FOLLOW_REQUEST_APPROVED)).isEmpty()
    }

    @Test
    fun `opened social notification stays read but becomes unavailable after a block`() {
        val followerOwner = member()
        val follower = publicBunch(followerOwner)
        val targetOwner = member()
        val target = publicBunch(targetOwner)
        val notification = run {
            social.follow(followerOwner, follower, target)
            socialNotifications(target, NotificationType.BUNCH_FOLLOWED).single()
        }

        val opened = notificationService.open(targetOwner, target, notification.id)
        assertThat(opened.available).isTrue()
        assertThat(opened.target).isNotNull

        social.block(targetOwner, target, follower)
        val unavailable = notificationService.open(targetOwner, target, notification.id)
        assertThat(unavailable.available).isFalse()
        assertThat(unavailable.target).isNull()
        assertThat(unavailable.readAt).isEqualTo(opened.readAt)
        assertThat(notifications.findById(notification.id).orElseThrow().readAt)
            .isEqualTo(opened.readAt)
    }

    private fun publicBunch(owner: Long): Long = bunches.create(
        owner,
        UUID.randomUUID(),
        CreateBunchRequest(uniqueName(), "공개 번치", Visibility.PUBLIC, CharacterType.TYPE_2)
    ).id

    private fun socialNotifications(bunchId: Long, type: NotificationType): List<Notification> =
        notifications.findAll()
            .filter { it.bunchId == bunchId && it.type == type }
            .sortedBy { it.id }

    private fun bunchName(id: Long): String = jdbc.queryForObject(
        "SELECT name FROM bunches WHERE id = ?",
        String::class.java,
        id
    )!!

    private fun reportCount(reporterBunchId: Long, targetBunchId: Long): Long = jdbc.queryForObject(
        "SELECT count(*) FROM bunch_profile_reports WHERE reporter_bunch_id = ? AND target_bunch_id = ?",
        Long::class.java,
        reporterBunchId,
        targetBunchId
    )!!

    private fun rejectWrites(table: String, operation: String, action: () -> Unit) {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "bunch_social_reject_$suffix"
        val trigger = "bunch_social_trigger_$suffix"
        try {
            jdbc.execute(
                """CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS
                    'BEGIN RAISE EXCEPTION ''injected social write failure''; END'"""
            )
            jdbc.execute(
                """CREATE TRIGGER $trigger BEFORE $operation ON $table
                    FOR EACH ROW EXECUTE FUNCTION $function()"""
            )
            action()
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS $trigger ON $table")
            jdbc.execute("DROP FUNCTION IF EXISTS $function()")
        }
    }

    private fun assertExpectedBlockVisibilityFailure(result: Result<*>) {
        if (result.isSuccess) return
        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(BusinessException::class.java)
        assertThat((error as BusinessException).errorCode)
            .isEqualTo(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
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
        error("social mutation did not reach the held bunch lock")
    }
}
