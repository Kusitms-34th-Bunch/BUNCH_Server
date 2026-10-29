package com.bunch.bundle.service

import com.bunch.bundle.domain.BundleEventType
import com.bunch.global.redis.RefreshTokenRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import java.nio.ByteBuffer

@Component
class BundleRealtimeOutboundInterceptor(
    private val sessions: RefreshTokenRepository,
    private val access: BundleAccessService,
    private val registry: BundleRealtimeSessionRegistry,
    private val mapper: ObjectMapper
) : ChannelInterceptor {
    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = StompHeaderAccessor.wrap(message)
        // Broker deliveries are SimpMessageType.MESSAGE even when the STOMP command has
        // not been restored by the protocol handler. Authorize those deliveries by the
        // semantic message type so a null command cannot bypass this interceptor.
        if (accessor.messageType != SimpMessageType.MESSAGE) return message

        val connectionId = accessor.sessionId ?: return null
        val identity = registry.identity(connectionId) ?: return null
        if (!isActive(connectionId, identity)) return null

        val destination = accessor.destination ?: return null
        val payload = payloadTree(message.payload) ?: return null
        return when {
            privateDelivery(connectionId, destination) ->
                message.takeIf { privatePayloadIsReadable(identity.memberId, payload) }

            BundleRealtimeDestinations.parseTopic(destination) != null ->
                topicDelivery(connectionId, destination, identity.memberId, payload, message)

            else -> null
        }
    }

    private fun topicDelivery(
        connectionId: String,
        destination: String,
        memberId: Long,
        payload: JsonNode,
        message: Message<*>
    ): Message<*>? {
        val subscription = registry.topicSubscription(connectionId, destination) ?: return null
        val bunchId = payload.longField("bunchId") ?: return null
        val bundleId = payload.longField("bundleId") ?: return null
        if (subscription.bunchId != bunchId || subscription.bundleId != bundleId) return null

        val eventType = payload["type"]?.takeIf { it.isTextual }?.asText()
        if (eventType == BundleEventType.DELETED.name) {
            return runCatching {
                access.requireMember(memberId, bunchId)
                message
            }.getOrNull()
        }
        return runCatching {
            access.read(memberId, bunchId, bundleId)
            message
        }.getOrNull()
    }

    private fun privateDelivery(connectionId: String, destination: String): Boolean =
        listOf(
            BundleRealtimeDestinations.LEASE_QUEUE,
            BundleRealtimeDestinations.ACTIVITY_QUEUE
        ).any { rawDestination ->
            registry.hasSubscription(connectionId, rawDestination) &&
                BundleRealtimeDestinations.isPrivateDelivery(destination, rawDestination)
        }

    private fun privatePayloadIsReadable(memberId: Long, payload: JsonNode): Boolean {
        val bunchId = payload.longField("bunchId") ?: return false
        val bundleId = payload.longField("bundleId") ?: return false
        return runCatching {
            access.read(memberId, bunchId, bundleId)
            true
        }.getOrDefault(false)
    }

    private fun isActive(connectionId: String, identity: BundleRealtimeIdentity): Boolean {
        val active = try {
            sessions.isActive(identity.authSessionId, identity.memberId)
        } catch (_: RuntimeException) {
            false
        }
        if (!active) registry.remove(connectionId)
        return active
    }

    private fun payloadTree(payload: Any?): JsonNode? = runCatching {
        when (payload) {
            is ByteArray -> mapper.readTree(payload)
            is String -> mapper.readTree(payload)
            is ByteBuffer -> {
                val copy = payload.asReadOnlyBuffer()
                val bytes = ByteArray(copy.remaining())
                copy.get(bytes)
                mapper.readTree(bytes)
            }
            null -> null
            else -> mapper.valueToTree(payload)
        }
    }.getOrNull()

    private fun JsonNode.longField(name: String): Long? = this[name]
        ?.takeIf { it.isIntegralNumber }
        ?.asLong()
        ?.takeIf { it > 0 }
}
