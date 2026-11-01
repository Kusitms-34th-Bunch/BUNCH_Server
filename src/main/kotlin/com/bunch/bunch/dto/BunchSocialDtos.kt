package com.bunch.bunch.dto

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchFollow
import com.bunch.bunch.domain.BunchFollowStatus
import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.Visibility
import java.time.Instant

enum class FollowState {
    NONE,
    REQUESTED,
    FOLLOWING
}

enum class FollowDecision {
    APPROVE,
    REJECT
}

enum class FollowDecisionState {
    APPROVED,
    REJECTED,
    UNAVAILABLE
}

data class FollowDecisionRequest(val decision: FollowDecision)

data class FollowResult(val followState: FollowState, val followId: Long?) {
    companion object {
        fun from(follow: BunchFollow?) = FollowResult(
            when (follow?.status) {
                BunchFollowStatus.PENDING -> FollowState.REQUESTED
                BunchFollowStatus.APPROVED -> FollowState.FOLLOWING
                null -> FollowState.NONE
            },
            follow?.id
        )
    }
}

data class FollowCancellationResult(
    val followState: FollowState,
    val followId: Long?,
    val cancelled: FollowState
)

data class FollowDecisionResult(val requestId: Long, val applied: Boolean, val state: FollowDecisionState)

data class BunchSocialCard(
    val id: Long,
    val name: String,
    val imageUrl: String?,
    val characterType: CharacterType,
    val visibility: Visibility
) {
    companion object {
        fun from(bunch: Bunch) = BunchSocialCard(
            bunch.id,
            bunch.name,
            bunch.imageUrl,
            bunch.characterType,
            bunch.visibility
        )
    }
}

data class BunchRelationItem(
    val bunch: BunchSocialCard,
    val followState: FollowState,
    val followId: Long?,
    val followedAt: Instant
)

data class BunchFollowRequestItem(val id: Long, val bunch: BunchSocialCard, val requestedAt: Instant)

data class BunchSocialPage<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val hasNext: Boolean
)
