package com.bunch.bundle.dto

import com.bunch.bundle.domain.BundleActivityType
import com.bunch.bundle.domain.BundleEventType
import java.time.Instant

data class BundleActor(
    val memberId: Long,
    val sessionId: String
)

data class BundleRealtimeMessage<T>(
    val type: BundleEventType,
    val bunchId: Long?,
    val bundleId: Long?,
    val revision: Long? = null,
    val requestId: String? = null,
    val data: T
)

data class BundleActivityData(val memberId: Long, val activity: BundleActivityType)

data class BundleCommandRejectedData(val code: String)

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

/**
 * Emitted synchronously BEFORE edit removals, inside the bundle write transaction.
 * Pin listeners must join that transaction; bundle deletion never emits this event.
 */
data class BundleLocationsRemovedEvent(
    val bundleId: Long,
    val locationIds: List<Long>
)

data class BundleActivityCommand(
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

data class BundlePresenceCommand(
    val action: String,
    val target: String? = null,
    val text: String? = null
)

data class BundlePresenceMember(
    val memberId: Long,
    val expiresAt: Instant
)

data class BundleTypingState(
    val memberId: Long,
    val target: String,
    val text: String,
    val expiresAt: Instant
)

data class BundleEditingState(
    val memberId: Long,
    val target: String,
    val expiresAt: Instant
)

data class BundlePresenceData(
    val members: List<BundlePresenceMember>,
    val typing: List<BundleTypingState>,
    val editing: List<BundleEditingState> = emptyList()
) {
    fun toMessage(bunchId: Long, bundleId: Long) = BundleRealtimeMessage(
        type = BundleEventType.PRESENCE,
        bunchId = bunchId,
        bundleId = bundleId,
        data = this
    )
}
