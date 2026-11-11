package com.bunch.bundle.dto

import com.bunch.bundle.domain.BundleEventType
import java.time.Instant

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
