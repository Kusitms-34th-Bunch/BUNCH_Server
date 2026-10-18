package com.bunch.bunch.domain

// Stored in the membership's existing join_requests JSONB column.
data class JoinRequest(val fingerprint: String, val outcome: JoinOutcome)
