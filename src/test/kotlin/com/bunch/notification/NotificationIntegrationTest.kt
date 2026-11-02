package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchAccessNotice
import com.bunch.bunch.domain.BunchAccessNoticeReason
import com.bunch.bunch.domain.BunchAccessNoticeRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.service.BundleAccessService
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.global.exception.ErrorCode
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.service.NotificationRetention
import com.bunch.notification.service.NotificationService
import com.bunch.notification.service.NotificationWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class NotificationIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var notifications: NotificationRepository
    @Autowired
    private lateinit var service: NotificationService
    @Autowired
    private lateinit var writer: NotificationWriter

    @Autowired
    private lateinit var access: BundleAccessService
    @Autowired
    private lateinit var retention: NotificationRetention
    @Autowired
    private lateinit var bundles: BundleRepository
    @Autowired
    private lateinit var participants: BundleParticipantRepository
    @Autowired
    private lateinit var accessNotices: BunchAccessNoticeRepository
    @Autowired
    private lateinit var transaction: TransactionTemplate

    @MockitoBean
    private lateinit var clock: Clock

    private lateinit var now: Instant

    @BeforeEach
    fun time() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        `when`(clock.instant()).thenReturn(now)
    }

    @AfterEach
    fun clearBundles() {
        jdbc.update("DELETE FROM bundles")
    }

    @Test
    fun `30 day expiry and latest 100 limit preserve all unread and scope`() {
        val owner = member()
        val bunch = create(owner).id
        val otherBunch = create(owner).id
        repeat(105) { save(owner, bunch, now.minusSeconds(it.toLong())) }
        val boundary = save(owner, bunch, now.minus(Duration.ofDays(30)))
        save(owner, bunch, now.minus(Duration.ofDays(31)))
        save(owner, otherBunch, now)
        val result = service.list(owner, bunch, 0, 50)
        assertThat(result.totalElements).isEqualTo(100)
        assertThat(result.unreadCount).isEqualTo(105)
        assertThat(result.items).hasSize(50)
        assertThat(result.hasNext).isTrue()
        assertThat(service.list(owner, bunch, 1, 50).hasNext).isFalse()
        assertThat(service.list(owner, bunch, Int.MAX_VALUE, 50).items).isEmpty()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.read(owner, bunch, boundary.id) }
        assertThat(service.readAll(owner, bunch).unreadCount).isZero()
        assertThat(service.list(owner, otherBunch, 0, 20).unreadCount).isEqualTo(1)
        assertThat(notifications.findById(boundary.id).orElseThrow().readAt).isNull()
        assertThat(retention.deleteExpired()).isEqualTo(2)
        assertThat(retention.deleteExpired()).isZero()
        assertThat(service.list(owner, bunch, 0, 20).totalElements).isEqualTo(100)
    }

    @Test
    fun `timestamp ties use descending id read is idempotent and private`() {
        val owner = member()
        val bunch = create(owner).id
        val first = save(owner, bunch, now)
        val second = save(owner, bunch, now)
        assertThat(service.list(owner, bunch, 0, 20).items.map { it.id }).containsExactly(second.id, first.id)
        val read = service.read(owner, bunch, first.id)
        `when`(clock.instant()).thenReturn(now.plusSeconds(30))
        assertThat(service.read(owner, bunch, first.id).readAt).isEqualTo(read.readAt)
        val other = member()
        join(other, issue(owner, bunch))
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.read(other, bunch, first.id) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.open(other, bunch, first.id) }
        assertThat(service.list(other, bunch, 0, 20).items).isEmpty()
        assertError(ErrorCode.FORBIDDEN) { service.list(member(), bunch, 0, 20) }
    }

    @Test
    fun `open rechecks draft participation and publication and keeps read after access disappears`() {
        val owner = member()
        val bunch = create(owner).id
        val draft = bundles.saveAndFlush(Bundle(bunchId = bunch, createdBy = owner))
        val notification = save(owner, bunch, now, NotificationTarget(NotificationTargetKind.DRAFT, bunch, draft.id))
        assertThat(service.open(owner, bunch, notification.id).available).isFalse()
        participants.saveAndFlush(BundleParticipant(bundleId = draft.id, memberId = owner))
        assertThat(service.open(owner, bunch, notification.id).available).isTrue()
        jdbc.update("UPDATE bundles SET status = 'PUBLISHED' WHERE id = ?", draft.id)
        assertThat(service.open(owner, bunch, notification.id).target).isNull()
        val published = save(owner, bunch, now, NotificationTarget(NotificationTargetKind.BUNDLE, bunch, draft.id))
        assertThat(service.open(owner, bunch, published.id).available).isTrue()
        bundles.deleteById(draft.id)
        val unavailable = service.open(owner, bunch, published.id)
        assertThat(unavailable.available).isFalse()
        assertThat(unavailable.target).isNull()
        assertThat(notifications.findById(published.id).orElseThrow().readAt).isEqualTo(unavailable.readAt)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", owner)
        assertThat(service.open(owner, bunch, published.id).available).isFalse()
    }

    @Test
    fun `other bunch profile open uses the server current after its inbox membership ends`() {
        val owner = member()
        val inboxBunch = create(owner).id
        val fallbackBunch = create(owner).id
        val targetOwner = member()
        val targetBunch = create(targetOwner).id
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", targetBunch)
        val fallbackLastUsed = lastUsedAt(owner, fallbackBunch)
        val notification = save(
            owner,
            inboxBunch,
            now,
            NotificationTarget(
                NotificationTargetKind.OTHER_BUNCH_PROFILE,
                inboxBunch,
                targetBunchId = targetBunch
            )
        )
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = CURRENT_TIMESTAMP " +
                "WHERE member_id = ? AND bunch_id = ?",
            owner,
            inboxBunch
        )

        val opened = service.open(owner, inboxBunch, notification.id)

        assertThat(opened.available).isTrue()
        assertThat(opened.target?.targetBunchId).isEqualTo(targetBunch)
        assertThat(lastUsedAt(owner, fallbackBunch)).isEqualTo(fallbackLastUsed)
    }

    @Test
    fun `other bunch profile open allows a public target for a completed zero membership user`() {
        val recipient = member()
        val sourceOwner = member()
        val sourceBunch = create(sourceOwner).id
        val targetOwner = member()
        val targetBunch = create(targetOwner).id
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", targetBunch)
        val notification = save(
            recipient,
            sourceBunch,
            now,
            NotificationTarget(
                NotificationTargetKind.OTHER_BUNCH_PROFILE,
                sourceBunch,
                targetBunchId = targetBunch
            )
        )

        val opened = service.open(recipient, sourceBunch, notification.id)

        assertThat(opened.available).isTrue()
        assertThat(opened.target?.targetBunchId).isEqualTo(targetBunch)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_memberships WHERE member_id = ? AND status = 'ACTIVE'",
            Long::class.java,
            recipient
        )).isZero()
    }

    @Test
    fun `other bunch profile access notice makes open unavailable without losing the read`() {
        val owner = member()
        val sourceBunch = create(owner).id
        val targetOwner = member()
        val targetBunch = create(targetOwner).id
        join(owner, issue(targetOwner, targetBunch))
        val membershipId = jdbc.queryForObject(
            "SELECT id FROM bunch_memberships WHERE member_id = ? AND bunch_id = ? AND status = 'ACTIVE'",
            Long::class.java,
            owner,
            targetBunch
        )!!
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
            membershipId
        )
        val occurredAt = now.minusSeconds(1)
        accessNotices.saveAndFlush(
            BunchAccessNotice(
                recipientMemberId = owner,
                bunchId = targetBunch,
                membershipId = membershipId,
                bunchName = "target",
                characterType = com.bunch.bunch.domain.CharacterType.TYPE_2,
                reason = BunchAccessNoticeReason.REMOVED,
                occurredAt = occurredAt,
                expiresAt = now.plus(Duration.ofDays(1))
            )
        )
        val notification = save(
            owner,
            sourceBunch,
            now,
            NotificationTarget(
                NotificationTargetKind.OTHER_BUNCH_PROFILE,
                sourceBunch,
                targetBunchId = targetBunch
            )
        )

        val opened = service.open(owner, sourceBunch, notification.id)

        assertThat(opened.available).isFalse()
        assertThat(opened.target).isNull()
        assertThat(opened.readAt).isNotNull()
        assertThat(notifications.findById(notification.id).orElseThrow().readAt)
            .isEqualTo(opened.readAt)
    }

    @Test
    fun `activity merges actors totals snapshots and read state for fixed ten minute window`() {
        val fixture = draftFixture()
        val (owner, other, bunch, bundle) = fixture
        activity(owner, bunch, bundle, 2)
        val first = activities(other, bunch).single()
        val title = first.title
        service.read(other, bunch, first.id)
        bundles.findById(bundle).orElseThrow().also { it.title = "changed"; bundles.saveAndFlush(it) }
        `when`(clock.instant()).thenReturn(now.plusSeconds(599))
        activity(owner, bunch, bundle, 3)
        val merged = activities(other, bunch).single()
        assertThat(merged.id).isEqualTo(first.id)
        assertThat(merged.title).isEqualTo(title)
        assertThat(merged.createdAt).isEqualTo(now)
        assertThat(merged.groupUntil).isEqualTo(now.plusSeconds(600))
        assertThat(merged.activityData!!.photoCount).isEqualTo(5)
        assertThat(merged.readAt).isNull()
        // The actor receives no copy of their own activity.
        assertThat(activities(owner, bunch)).isEmpty()
        `when`(clock.instant()).thenReturn(now.plusSeconds(600))
        activity(owner, bunch, bundle, 1)
        assertThat(activities(other, bunch)).hasSize(2)
    }

    @Test
    fun `two actors aggregate distinct snapshots without notifying an actor of their own action`() {
        val (owner, other, bunch, bundle) = draftFixture()
        val third = member()
        join(third, issue(owner, bunch))
        participants.saveAndFlush(BundleParticipant(bundleId = bundle, memberId = third))
        val originalName = members.findById(owner).orElseThrow().nickname!!
        activity(owner, bunch, bundle, 3)
        jdbc.update("UPDATE members SET nickname = ? WHERE id = ?", uniqueName(), owner)
        activity(other, bunch, bundle, 4)
        val combined = activities(third, bunch).single()
        assertThat(combined.activityData!!.actors).hasSize(2)
        assertThat(combined.activityData!!.actors[owner]).isEqualTo(originalName)
        assertThat(combined.body).isEqualTo("${originalName}님 외 1명이 사진 7장을 추가했어요.")
        assertThat(activities(owner, bunch).single().activityData!!.photoCount).isEqualTo(4)
        assertThat(activities(other, bunch).single().activityData!!.photoCount).isEqualTo(3)
    }

    @Test
    fun `jsonb actor key reordering preserves the first actor in merged body`() {
        val (owner, other, bunch, bundle) = draftFixture()
        val third = member()
        join(third, issue(owner, bunch))
        participants.saveAndFlush(BundleParticipant(bundleId = bundle, memberId = third))
        val firstName = members.findById(other).orElseThrow().nickname!!
        // The later member ID acts first; jsonb sorts the owner ID ahead of it.
        activity(other, bunch, bundle, 3)
        activity(owner, bunch, bundle, 4)
        val combined = activities(third, bunch).single()
        assertThat(combined.body).isEqualTo("${firstName}님 외 1명이 사진 7장을 추가했어요.")
    }

    @Test
    fun `concurrent first activities serialize under bundle lock without lost totals`() {
        val (owner, other, bunch, bundle) = draftFixture()
        concurrently(List(6) { { activity(owner, bunch, bundle, 1) } }).forEach { it.getOrThrow() }
        val activity = activities(other, bunch).single()
        assertThat(activity.activityData!!.photoCount).isEqualTo(6)
        assertThat(activity.activityData!!.actors).hasSize(1)
    }

    @Test
    fun `read all and aggregation follow both database update orders including a waiting transaction`() {
        val (owner, other, bunch, bundle) = draftFixture()
        activity(owner, bunch, bundle, 1)
        service.readAll(other, bunch)
        activity(owner, bunch, bundle, 1)
        assertThat(service.list(other, bunch, 0, 20).unreadCount).isEqualTo(1)
        service.readAll(other, bunch)
        assertThat(service.list(other, bunch, 0, 20).unreadCount).isZero()
        // Start unread so PostgreSQL's UPDATE predicate includes the locked row.
        activity(owner, bunch, bundle, 1)
        inDatabaseOrder(
            first = { writer.draftActivity(owner, access.lock(owner, bunch, bundle), UUID.randomUUID().toString(), 1) },
            waiting = { service.readAll(other, bunch) }
        )
        assertThat(service.list(other, bunch, 0, 20).unreadCount).isZero()
        activity(owner, bunch, bundle, 1)
        inDatabaseOrder(
            first = { service.readAll(other, bunch) },
            waiting = { writer.draftActivity(owner, access.lock(owner, bunch, bundle), UUID.randomUUID().toString(), 1) }
        )
        assertThat(service.list(other, bunch, 0, 20).unreadCount).isEqualTo(1)
    }

    @Test
    fun `business rollback discards activity insert and merge`() {
        val (owner, other, bunch, bundle) = draftFixture()
        transaction.executeWithoutResult { status ->
            writer.draftActivity(owner, access.lock(owner, bunch, bundle), "first", 2)
            status.setRollbackOnly()
        }
        assertThat(activities(other, bunch)).isEmpty()
        activity(owner, bunch, bundle, 1)
        transaction.executeWithoutResult { status ->
            writer.draftActivity(owner, access.lock(owner, bunch, bundle), "second", 3)
            status.setRollbackOnly()
        }
        assertThat(activities(other, bunch).single().activityData!!.photoCount).isEqualTo(1)
    }

    private fun inDatabaseOrder(first: () -> Unit, waiting: () -> Unit) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val backend = java.util.concurrent.CompletableFuture<Int>()
        try {
            lateinit var future: java.util.concurrent.Future<*>
            transaction.executeWithoutResult {
                first()
                future = executor.submit {
                    transaction.executeWithoutResult {
                        backend.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        waiting()
                    }
                }
                val pid = backend.get(5, java.util.concurrent.TimeUnit.SECONDS)
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))", Int::class.java, pid) == 0) {
                    check(System.nanoTime() < deadline) { "Contending transaction did not wait on the row lock" }
                    Thread.sleep(10)
                }
            }
            future.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    private fun save(
        owner: Long,
        bunch: Long,
        at: Instant,
        target: NotificationTarget = NotificationTarget(NotificationTargetKind.MEMBERS, bunch)
    ) = notifications.saveAndFlush(
        Notification(
            recipientMemberId = owner,
            bunchId = bunch,
            type = NotificationType.MEMBER_JOINED,
            dedupeKey = UUID.randomUUID().toString(),
            title = "snapshot",
            body = "snapshot body",
            target = target,
            createdAt = at
        )
    )

    private fun draftFixture(): List<Long> {
        val owner = member()
        val other = member()
        val bunch = create(owner).id
        join(other, issue(owner, bunch))
        val bundle = bundles.saveAndFlush(Bundle(bunchId = bunch, createdBy = owner, title = "original")).id
        participants.saveAllAndFlush(listOf(
            BundleParticipant(bundleId = bundle, memberId = owner),
            BundleParticipant(bundleId = bundle, memberId = other)
        ))
        return listOf(owner, other, bunch, bundle)
    }

    private fun activity(actor: Long, bunch: Long, bundle: Long, count: Int) {
        transaction.executeWithoutResult {
            writer.draftActivity(actor, access.lock(actor, bunch, bundle), UUID.randomUUID().toString(), count)
        }
    }

    private fun activities(member: Long, bunch: Long) = notifications.latest(member, bunch, now.minusSeconds(1))
        .filter { it.type == NotificationType.DRAFT_ACTIVITY }

    private fun lastUsedAt(memberId: Long, bunchId: Long): Instant =
        jdbc.queryForObject(
            "SELECT last_used_at FROM bunch_memberships WHERE member_id = ? AND bunch_id = ?",
            java.sql.Timestamp::class.java,
            memberId,
            bunchId
        )!!.toInstant()
}
