package com.bunch.bunch.dto

import com.bunch.bunch.domain.BunchAccessNotice
import com.bunch.bunch.domain.BunchAccessNoticeReason
import com.bunch.bunch.domain.CharacterType
import java.time.Instant

data class BunchMembershipExitRequest(
    val actorMembershipId: Long,
    val expectedMembershipVersion: Long,
    val successorMembershipId: Long? = null
)

data class BunchDepartureResponse(
    val bunchId: Long,
    val departedMembershipId: Long,
    val outcome: String,
    val defaultBunchId: Long?
)

data class BunchRemovalResponse(
    val bunchId: Long,
    val removedMembershipId: Long,
    val membershipVersion: Long,
    val memberCount: Long
)

data class BunchAccessNoticeResponse(
    val id: Long,
    val bunchId: Long,
    val name: String,
    val characterType: CharacterType,
    val reason: BunchAccessNoticeReason,
    val occurredAt: Instant,
    val expiresAt: Instant
) {
    companion object {
        fun from(notice: BunchAccessNotice) = BunchAccessNoticeResponse(
            id = notice.id,
            bunchId = notice.bunchId,
            name = notice.bunchName,
            characterType = notice.characterType,
            reason = notice.reason,
            occurredAt = notice.occurredAt,
            expiresAt = notice.expiresAt
        )
    }
}
