package com.bunch.bundle.service

/**
 * Emitted synchronously BEFORE edit removals, inside the bundle write transaction.
 * Pin listeners must join that transaction; bundle deletion never emits this event.
 */
data class BundleLocationsRemovedEvent(
    val bundleId: Long,
    val locationIds: List<Long>
)
