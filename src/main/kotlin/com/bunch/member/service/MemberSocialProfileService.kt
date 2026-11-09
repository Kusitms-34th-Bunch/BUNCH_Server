package com.bunch.member.service

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.dto.BunchSocialCard
import com.bunch.bunch.dto.FollowResult
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberBunchNameComparator
import com.bunch.member.domain.MemberSocialProfileRepository
import com.bunch.member.dto.MemberSocialBunchItem
import com.bunch.member.dto.MemberSocialProfileResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class MemberSocialProfileService(
    private val access: FeedAccessService,
    private val profiles: MemberSocialProfileRepository,
    private val follows: BunchFollowRepository
) {
    fun get(userId: Long, memberId: Long): MemberSocialProfileResponse {
        val viewer = access.viewer(userId)
        if (memberId <= 0) throw BusinessException(ErrorCode.INVALID_INPUT)
        val member = profiles.findPublicMember(memberId) ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        val bunches = profiles.findVisibleActiveBunches(memberId, viewer.currentBunchId)
            .sortedWith(compareBy<Bunch, String>(MemberBunchNameComparator) { it.name }.thenBy { it.id })
        val relations = if (viewer.currentBunchId != null && bunches.isNotEmpty()) {
            follows.findAllByFollowerBunchIdAndFollowedBunchIdIn(viewer.currentBunchId, bunches.map { it.id })
                .associateBy { it.followedBunchId }
        } else {
            emptyMap()
        }
        return MemberSocialProfileResponse(
            member.id,
            member.nickname,
            member.imageUrl,
            viewer.currentBunchId,
            bunches.map { bunch ->
                val relation = FollowResult.from(relations[bunch.id])
                MemberSocialBunchItem(
                    BunchSocialCard.from(bunch),
                    relation.followState,
                    relation.followId,
                    viewer.currentBunchId != bunch.id
                )
            }
        )
    }
}
