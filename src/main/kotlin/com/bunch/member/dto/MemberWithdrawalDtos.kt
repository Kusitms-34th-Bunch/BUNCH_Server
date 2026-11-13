package com.bunch.member.dto

// The bunches the member still has to hand over before leaving the service (7.6.2, 7.6.3).
data class WithdrawalCheckResponse(val ownerBunches: List<WithdrawalOwnerBunch>)

data class WithdrawalOwnerBunch(
    val bunchId: Long,
    val name: String,
    val imageUrl: String?,
    val myMembershipId: Long,
    val membershipVersion: Long
)
