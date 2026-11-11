package com.bunch.bundle.service

import com.bunch.bunch.dto.BunchMembershipChangedEvent
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.photo.service.PhotoChangedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Bridges source-domain changes to the existing committed bundle realtime event.
 *
 * The listener is deliberately synchronous: it reads the affected draft state and
 * publishes a [BundleChangedEvent] while the source write transaction is active.
 * [BundleRealtimeEventListener] remains the only after-commit broker publisher, so
 * a source transaction that rolls back cannot deliver a refresh message.
 */
@Component
class BundleRefreshEventListener(
    private val bundles: BundleRepository,
    private val bundleService: BundleService,
    private val events: ApplicationEventPublisher
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun membershipChanged(event: BunchMembershipChangedEvent) {
        draftBundles(event.bunchId).forEach { bundle ->
            publish(bundle, BundleEventType.PERMISSIONS, null)
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun photoChanged(event: PhotoChangedEvent) {
        // PhotoService announces each date a photo leaves or enters. A null date is
        // not part of the recommendation input and cannot change a chip list.
        if (event.capturedDate == null) return

        draftBundles(event.bunchId)
            .filter { it.date == null }
            .forEach { bundle ->
                publish(
                    bundle,
                    BundleEventType.DATE_CHIPS,
                    mapOf("dateChips" to bundleService.dateChips(bundle))
                )
            }
    }

    private fun draftBundles(bunchId: Long): List<Bundle> =
        bundles.findAllByBunchIdOrderByIdAsc(bunchId).filter { it.status == BundleStatus.DRAFT }

    private fun publish(bundle: Bundle, type: BundleEventType, changes: Any?) {
        events.publishEvent(
            BundleChangedEvent(
                bunchId = bundle.bunchId,
                bundleId = bundle.id,
                revision = bundle.revision,
                requestId = UUID.randomUUID().toString(),
                type = type,
                changes = changes
            )
        )
    }
}
