package com.bunch.bundle.service

import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.bundle.dto.BundleActivityEvent
import com.bunch.bundle.dto.BundleLeaseStateChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class BundleRealtimeEventListener(
    private val messaging: SimpMessagingTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun changed(event: BundleChangedEvent) {
        send(event.bunchId, event.bundleId, event.type) {
            messaging.convertAndSend(BundleRealtimeDestinations.topic(event.bunchId, event.bundleId), event.toMessage())
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun leaseStateChanged(event: BundleLeaseStateChangedEvent) {
        send(event.bunchId, event.bundleId, BundleEventType.LEASES) {
            messaging.convertAndSend(BundleRealtimeDestinations.topic(event.bunchId, event.bundleId), event.toMessage())
        }
    }

    @EventListener
    fun activity(event: BundleActivityEvent) {
        send(event.bunchId, event.bundleId, BundleEventType.ACTIVITY) {
            messaging.convertAndSend(BundleRealtimeDestinations.topic(event.bunchId, event.bundleId), event.toMessage())
        }
    }

    private fun send(bunchId: Long, bundleId: Long, type: BundleEventType, block: () -> Unit) {
        try {
            block()
        } catch (ex: RuntimeException) {
            // The transaction has already committed. Keep broker outages from turning a
            // successful REST write into a failed request, and never log credentials/payloads.
            logger.warn(
                "Bundle realtime delivery failed bunchId={} bundleId={} eventType={} cause={}",
                bunchId,
                bundleId,
                type,
                ex.javaClass.simpleName
            )
        }
    }
}
