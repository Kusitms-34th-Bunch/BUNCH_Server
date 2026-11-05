package com.bunch.bunch.dto

import com.bunch.bunch.domain.MembershipRole

data class ChangeMembershipRoleRequest(
    val actorMembershipId: Long,
    val expectedMembershipVersion: Long,
    val role: MembershipRole
)

data class OwnerTransferRequest(
    val actorMembershipId: Long,
    val expectedMembershipVersion: Long,
    val targetMembershipId: Long
)
