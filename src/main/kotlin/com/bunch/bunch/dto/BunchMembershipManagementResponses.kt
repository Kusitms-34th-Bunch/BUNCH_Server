package com.bunch.bunch.dto

import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.MembershipRole
import java.time.Instant

data class MyMembershipResponse(
    val membershipId: Long,
    val role: MembershipRole,
    val joinedAt: Instant
)

data class MembershipActions(
    val canGrantCoOwner: Boolean,
    val canRevokeCoOwner: Boolean,
    val canTransferOwner: Boolean,
    val canRemove: Boolean
)

data class ManagedMembershipResponse(
    val membershipId: Long,
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?,
    val role: MembershipRole,
    val isMe: Boolean,
    val joinedAt: Instant,
    val actions: MembershipActions
)

data class MembershipsResponse(
    val bunchId: Long,
    val membershipVersion: Long,
    val myMembership: MyMembershipResponse,
    val members: List<ManagedMembershipResponse>
)

data class MembershipMutationResult(
    val bunchId: Long,
    val membershipVersion: Long,
    val changedMembershipIds: List<Long>,
    val ownerMembershipId: Long
)

fun BunchMembership.toMyMembershipResponse() = MyMembershipResponse(
    membershipId = id,
    role = role,
    joinedAt = joinedAt
)
