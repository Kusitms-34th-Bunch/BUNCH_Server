package com.bunch.bunch.service

import com.bunch.bunch.domain.BunchListRepository
import com.bunch.bunch.dto.BunchCardResponse
import com.bunch.bunch.dto.MyBunchItem
import com.bunch.bunch.dto.MyBunchesResponse
import com.bunch.bunch.dto.toHomeMembershipResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.domain.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class BunchListService(
    private val members: MemberRepository,
    private val memberships: BunchListRepository,
    private val notices: BunchAccessNoticeService,
    private val notifications: NotificationRepository,
    private val clock: Clock
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(memberId: Long, currentBunchId: Long?): MyBunchesResponse {
        members.findById(memberId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()

        val active = memberships.findActiveByMemberId(memberId)
        val currentFirst = active.sortedWith(
            compareByDescending<com.bunch.bunch.domain.BunchMembership> {
                currentBunchId != null && it.bunch.id == currentBunchId
            }.thenByDescending { it.lastUsedAt }.thenByDescending { it.id }
        )
        val cutoff = clock.instant().minus(RETENTION)
        val activeIds = active.map { it.bunch.id }.toSet()
        val unavailable = notices.active(memberId)
            .filterNot { it.bunchId in activeIds }

        return MyBunchesResponse(
            activeCount = active.size.toLong(),
            maxActiveCount = MAX_ACTIVE_BUNCHES,
            defaultBunchId = active.firstOrNull()?.bunch?.id,
            activeBunches = currentFirst.map { membership ->
                MyBunchItem(
                    bunch = BunchCardResponse.from(membership.bunch),
                    myMembership = membership.toHomeMembershipResponse(),
                    unreadCount = notifications.countByRecipientMemberIdAndBunchIdAndCreatedAtAfterAndReadAtIsNull(
                        memberId,
                        membership.bunch.id,
                        cutoff
                    ),
                    lastUsedAt = membership.lastUsedAt
                )
            },
            unavailableBunches = unavailable
        )
    }

    companion object {
        const val MAX_ACTIVE_BUNCHES = 10
        private val RETENTION: Duration = Duration.ofDays(30)
    }
}
