package com.bunch.bunch.dto

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.InviteEligibility
import com.bunch.bunch.domain.JoinOutcome
import com.bunch.bunch.domain.MembershipRole
import java.time.Instant

data class NameAvailability(
    val normalizedValue: String,
    val available: Boolean
)

data class BunchResponse(
    val id: Long,
    val name: String,
    val characterType: CharacterType,
    val imageUrl: String?
) {
    companion object {
        fun from(bunch: Bunch) = BunchResponse(
            bunch.id,
            bunch.name,
            bunch.characterType,
            bunch.imageUrl
        )
    }
}

// Only what a member card needs. Birth year, gender and account state are never exposed (5.10.8).
data class BunchMemberResponse(
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?,
    val role: MembershipRole
) {
    companion object {
        fun from(membership: BunchMembership) = BunchMemberResponse(
            membership.member.id,
            membership.member.nickname,
            membership.member.imageUrl,
            membership.role
        )
    }
}

data class InvitationResponse(
    val url: String,
    val code: String,
    val expiresAt: Instant
)

data class InvitationCard(
    val name: String,
    val introduction: String,
    val imageUrl: String?,
    val inviterNickname: String?,
    val code: String
)

data class InvitationVerification(
    val card: InvitationCard,
    val eligibility: InviteEligibility
)

data class JoinResponse(
    val outcome: JoinOutcome,
    val bunch: BunchResponse
)
