package com.bunch.bundle.service

import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Component
class BundleRealtimeDisconnectListener(
    private val registry: BundleRealtimeSessionRegistry,
    private val presence: BundlePresenceService
) {
    @EventListener
    fun disconnected(event: SessionDisconnectEvent) {
        val connectionId = StompHeaderAccessor.wrap(event.message).sessionId ?: return
        registry.remove(connectionId)
        presence.disconnected(connectionId)
    }
}
