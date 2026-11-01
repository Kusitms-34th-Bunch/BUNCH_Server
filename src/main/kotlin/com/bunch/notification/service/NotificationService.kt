package com.bunch.notification.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.member.domain.MemberRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.service.BundleAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.dto.NotificationItem
import com.bunch.notification.dto.NotificationOpen
import com.bunch.notification.dto.NotificationPage
import com.bunch.notification.dto.NotificationRead
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.dto.NotificationUnread
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class NotificationService(
    private val notifications: NotificationRepository,
    private val access: BundleAccessService,
    private val bundles: BundleRepository,
    private val memberships: BunchMembershipRepository,
    private val members: MemberRepository,
    private val clock: Clock
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(memberId: Long, bunchId: Long, page: Int, size: Int): NotificationPage {
        access.requireMember(memberId, bunchId)
        if (page < 0 || size !in 1..50) throw BusinessException(ErrorCode.INVALID_INPUT)
        val cutoff = cutoff()
        val latest = notifications.latest(memberId, bunchId, cutoff)
        val offset = page.toLong() * size
        val items = if (offset >= latest.size) emptyList() else latest.drop(offset.toInt()).take(size)
        return NotificationPage(
            items = items.map(::item),
            page = page,
            size = size,
            totalElements = latest.size.toLong(),
            hasNext = offset + items.size < latest.size,
            unreadCount = unread(memberId, bunchId, cutoff)
        )
    }

    @Transactional
    fun read(memberId: Long, bunchId: Long, id: Long): NotificationRead {
        access.requireMember(memberId, bunchId)
        val notification = owned(memberId, bunchId, id)
        return NotificationRead(notification.id, markRead(notification))
    }

    @Transactional
    fun readAll(memberId: Long, bunchId: Long): NotificationUnread {
        access.requireMember(memberId, bunchId)
        val now = clock.instant()
        val cutoff = now.minus(RETENTION)
        notifications.readAll(memberId, bunchId, cutoff, now)
        return NotificationUnread(unread(memberId, bunchId, cutoff))
    }

    @Transactional
    fun open(memberId: Long, bunchId: Long, id: Long): NotificationOpen {
        // The notification still belongs to the recipient if membership/target disappeared.
        // Do not reject current membership before committing the read state.
        members.findById(memberId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }.requireCompleted()
        val notification = owned(memberId, bunchId, id)
        val readAt = markRead(notification)
        val available = available(memberId, notification.target)
        return NotificationOpen(id, readAt, available, notification.target.takeIf { available })
    }

    private fun available(memberId: Long, target: NotificationTarget): Boolean {
        if (memberships.findByMemberIdAndBunchIdAndStatus(memberId, target.bunchId) == null) return false
        return when (target.kind) {
            NotificationTargetKind.BUNCH_HOME,
            NotificationTargetKind.MEMBERS,
            NotificationTargetKind.ROLE_PERMISSIONS,
            NotificationTargetKind.DRAFT_LIST,
            NotificationTargetKind.CANDIDATES -> true
            NotificationTargetKind.DRAFT -> {
                val bundle = target.bundleId?.let { bundles.findById(it).orElse(null) }
                bundle != null && bundle.bunchId == target.bunchId && bundle.status == BundleStatus.DRAFT &&
                    access.isParticipant(memberId, bundle.id)
            }
            NotificationTargetKind.BUNDLE,
            NotificationTargetKind.BUNDLE_COMMENTS -> {
                val bundle = target.bundleId?.let { bundles.findById(it).orElse(null) }
                bundle != null && bundle.bunchId == target.bunchId && bundle.status == BundleStatus.PUBLISHED
            }
            // External relationship and access-notice producers are not implemented in this pass.
            // No stored payload is accepted as evidence of access to these destinations.
            NotificationTargetKind.OTHER_BUNCH_PROFILE,
            NotificationTargetKind.ACCESS_NOTICE -> false
        }
    }

    private fun owned(memberId: Long, bunchId: Long, id: Long): Notification =
        notifications.findOwnedLocked(id, memberId, bunchId, cutoff())
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

    private fun markRead(notification: Notification): Instant {
        if (notification.readAt == null) notification.readAt = clock.instant()
        return notification.readAt!!
    }

    private fun unread(memberId: Long, bunchId: Long, cutoff: Instant): Long =
        notifications.countByRecipientMemberIdAndBunchIdAndCreatedAtAfterAndReadAtIsNull(memberId, bunchId, cutoff)

    private fun cutoff(): Instant = clock.instant().minus(RETENTION)

    private fun item(n: Notification) = NotificationItem(n.id, n.type, n.title, n.body, n.createdAt, n.readAt, n.target)

    companion object {
        val RETENTION: Duration = Duration.ofDays(30)
    }
}
