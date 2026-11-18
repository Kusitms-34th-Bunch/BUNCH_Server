package com.bunch.member.dto

import com.bunch.global.oidc.AuthProvider
import jakarta.validation.constraints.Size

// The bunches the member still has to hand over before leaving the service (7.6.2, 7.6.3).
// `provider` tells the app whether to ask for Sign in with Apple again before the withdrawal.
data class WithdrawalCheckResponse(
    val ownerBunches: List<WithdrawalOwnerBunch>,
    val provider: AuthProvider? = null
)

data class WithdrawalOwnerBunch(
    val bunchId: Long,
    val name: String,
    val imageUrl: String?,
    val myMembershipId: Long,
    val membershipVersion: Long
)

// Only a member who signed in with Apple sends the code: it lets the server cancel the Apple connection.
data class WithdrawalRequest(
    @field:Size(max = 4096)
    val appleAuthorizationCode: String? = null
)
