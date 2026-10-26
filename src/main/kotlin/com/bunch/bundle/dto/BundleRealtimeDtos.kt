package com.bunch.bundle.dto

import com.bunch.bundle.domain.BundleActivityType
import com.bunch.bundle.domain.BundleEventType
import java.time.Instant
import java.util.UUID

data class BundleActor(
    val memberId: Long,
    val sessionId: String,
    val screenId: String
)

data class BundleRealtimeMessage<T>(
    val type: BundleEventType,
    val bunchId: Long?,
    val bundleId: Long?,
    val revision: Long? = null,
    val requestId: String? = null,
    val data: T
)

data class BundleLeaseData(
    val target: String,
    val token: UUID,
    val leaseVersion: Long,
    val expiresAt: Instant
)

data class BundleLeaseStatesData(val states: List<LeaseState>)

data class BundleActivityData(val memberId: Long, val activity: BundleActivityType)

data class BundleLeaseReleasedData(val target: String)

data class BundleCommandRejectedData(val code: String)

data class LeaseGrant(
    val bunchId: Long,
    val bundleId: Long,
    val target: String,
    val token: UUID,
    val leaseVersion: Long,
    val expiresAt: Instant,
    val revision: Long
) {
    fun toMessage(type: BundleEventType): BundleRealtimeMessage<BundleLeaseData> {
        require(type == BundleEventType.LEASE_GRANTED || type == BundleEventType.LEASE_HEARTBEAT)
        return BundleRealtimeMessage(
            type = type,
            bunchId = bunchId,
            bundleId = bundleId,
            revision = revision,
            data = BundleLeaseData(target, token, leaseVersion, expiresAt)
        )
    }
}

data class LeaseState(
    val target: String,
    val memberId: Long,
    val expiresAt: Instant,
    val leaseVersion: Long
)

data class BundleChangedEvent(
    val bunchId: Long,
    val bundleId: Long,
    val revision: Long,
    val requestId: String,
    val type: BundleEventType,
    val changes: Any?
) {
    fun toMessage() = BundleRealtimeMessage(type, bunchId, bundleId, revision, requestId, changes)
}

data class BundleLeaseStateChangedEvent(
    val bunchId: Long,
    val bundleId: Long,
    val revision: Long,
    val states: List<LeaseState>
) {
    fun toMessage() = BundleRealtimeMessage(
        type = BundleEventType.LEASES,
        bunchId = bunchId,
        bundleId = bundleId,
        revision = revision,
        data = BundleLeaseStatesData(states)
    )
}

data class BundleLeaseCommand(
    val target: String,
    val screenId: String,
    val token: UUID? = null
)

data class BundleActivityCommand(
    val screenId: String,
    val type: String = "COMMENT"
)

data class BundleActivityEvent(
    val bunchId: Long,
    val bundleId: Long,
    val memberId: Long,
    val type: BundleActivityType,
    val revision: Long
) {
    fun toMessage() = BundleRealtimeMessage(
        type = BundleEventType.ACTIVITY,
        bunchId = bunchId,
        bundleId = bundleId,
        revision = revision,
        data = BundleActivityData(memberId, type)
    )
}

data class BundleLeaseReleaseAck(
    val bunchId: Long,
    val bundleId: Long,
    val target: String,
    val revision: Long
) {
    fun toMessage() = BundleRealtimeMessage(
        type = BundleEventType.LEASE_RELEASED,
        bunchId = bunchId,
        bundleId = bundleId,
        revision = revision,
        data = BundleLeaseReleasedData(target)
    )
}
