package com.bunch.bunch.dto

/**
 * Published inside the transaction that changes an active bunch membership role.
 * The bundle refresh bridge consumes it synchronously and publishes one derived
 * BundleChangedEvent per open draft; the existing realtime listener delivers that
 * derived event only after the source transaction commits.
 */
data class BunchMembershipChangedEvent(val bunchId: Long)
