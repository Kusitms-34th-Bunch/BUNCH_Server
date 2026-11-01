package com.bunch.bunch.dto

data class BunchDeletionRequest(
    val actorMembershipId: Long,
    val expectedMembershipVersion: Long,
    val confirmation: String
)
