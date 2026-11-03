package com.bunch.bundle.service

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Component
class BundleRealtimeDisconnectListener(
    private val registry: BundleRealtimeSessionRegistry,
    private val leases: BundleLeaseService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun disconnected(event: SessionDisconnectEvent) {
        val connectionId = StompHeaderAccessor.wrap(event.message).sessionId ?: return
        registry.remove(connectionId)
        try {
            leases.clearConnection(connectionId)
        } catch (ex: RuntimeException) {
            logger.warn(
                "Bundle realtime disconnect cleanup failed cause={}",
                ex.javaClass.simpleName
            )
        }
    }
}
