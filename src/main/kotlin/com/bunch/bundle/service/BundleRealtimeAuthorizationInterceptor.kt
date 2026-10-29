package com.bunch.bundle.service

import com.bunch.bundle.domain.BundleActivityType
import com.bunch.bundle.domain.Bundle
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import java.util.Locale

@Component
class BundleRealtimeAuthorizationInterceptor(
    private val access: BundleAccessService,
    private val registry: BundleRealtimeSessionRegistry,
    private val mapper: ObjectMapper
) : ChannelInterceptor {
    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = StompHeaderAccessor.wrap(message)
        val connectionId = accessor.sessionId ?: return null
        if (accessor.command == StompCommand.DISCONNECT) return message
        val identity = registry.identity(connectionId) ?: return null
        return try {
            when (accessor.command) {
                StompCommand.SUBSCRIBE -> subscribe(message, accessor, identity.memberId, connectionId)
                StompCommand.UNSUBSCRIBE -> unsubscribe(message, accessor, connectionId)
                StompCommand.SEND -> send(message, accessor, identity.memberId)
                StompCommand.CONNECT -> message
                StompCommand.MESSAGE -> null
                null -> null
                else -> null
            }
        } catch (_: RuntimeException) {
            // Dropping a rejected frame avoids the protocol handler logging native headers
            // and session metadata while denying the operation and any subsequent delivery.
            null
        }
    }

    private fun subscribe(
        message: Message<*>,
        accessor: StompHeaderAccessor,
        memberId: Long,
        connectionId: String
    ): Message<*>? {
        val subscriptionId = accessor.subscriptionId?.takeIf { it.isNotBlank() } ?: return null
        val destination = accessor.destination ?: return null
        if (registry.hasSubscription(connectionId, destination)) return null

        if (BundleRealtimeDestinations.isPrivateSubscription(destination)) {
            if (!registry.addSubscription(connectionId, BundleRealtimeSubscription(subscriptionId, destination))) {
                return null
            }
            return message
        }

        val parsed = BundleRealtimeDestinations.parseTopic(destination) ?: return null
        val bundle = resolveTopic(memberId, parsed) ?: return null
        if (!registry.addSubscription(
                connectionId,
                BundleRealtimeSubscription(
                    id = subscriptionId,
                    destination = destination,
                    bunchId = bundle.bunchId,
                    bundleId = bundle.id
                )
            )
        ) {
            return null
        }
        return message
    }

    private fun unsubscribe(
        message: Message<*>,
        accessor: StompHeaderAccessor,
        connectionId: String
    ): Message<*>? {
        val subscriptionId = accessor.subscriptionId?.takeIf { it.isNotBlank() } ?: return null
        return if (registry.removeSubscription(connectionId, subscriptionId)) message else null
    }

    private fun send(message: Message<*>, accessor: StompHeaderAccessor, memberId: Long): Message<*>? {
        val parsed = BundleRealtimeDestinations.parseCommand(accessor.destination) ?: return null
        val bundle = resolveCommand(memberId, parsed) ?: return null
        if (parsed.command != BundleRealtimeCommand.ACTIVITY || activityType(message) == BundleActivityType.COMMENT.name) {
            access.requireParticipant(memberId, bundle)
        }
        return message
    }

    private fun activityType(message: Message<*>): String = runCatching {
        val body = when (val payload = message.payload) {
            is ByteArray -> mapper.readTree(payload)
            is String -> mapper.readTree(payload)
            else -> mapper.valueToTree(payload)
        }
        body.path("type").asText().trim().uppercase(Locale.ROOT)
    }.getOrDefault("")

    private fun resolveCommand(memberId: Long, destination: BundleRealtimeCommandDestination): Bundle? {
        val bundle = access.read(memberId, destination.bunchId, destination.bundleId)
        return bundle.takeIf { it.bunchId == destination.bunchId }
    }

    private fun resolveTopic(memberId: Long, destination: BundleRealtimeTopicDestination): Bundle? {
        return access.read(memberId, destination.bunchId, destination.bundleId)
            .takeIf { it.bunchId == destination.bunchId }
    }
}
