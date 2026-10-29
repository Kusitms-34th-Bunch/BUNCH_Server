package com.bunch.bundle.service

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.JwtSessionDetails
import com.bunch.global.security.TokenProvider
import org.springframework.http.HttpHeaders
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class BundleRealtimeAuthInterceptor(
    private val tokenProvider: TokenProvider,
    private val sessions: RefreshTokenRepository,
    private val registry: BundleRealtimeSessionRegistry
) : ChannelInterceptor {
    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
            ?: StompHeaderAccessor.wrap(message)
        val connectionId = accessor.sessionId ?: return null
        return when (accessor.command) {
            StompCommand.CONNECT -> authenticate(message, accessor, connectionId)
            StompCommand.DISCONNECT -> {
                registry.remove(connectionId)
                message
            }
            else -> authorizeExisting(message, accessor, connectionId)
        }
    }

    private fun authenticate(
        message: Message<*>,
        accessor: StompHeaderAccessor,
        connectionId: String
    ): Message<*>? {
        if (registry.identity(connectionId) != null) return null
        val header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION) ?: return null
        val token = header.takeIf { it.startsWith("Bearer ") }
            ?.substringAfter("Bearer ")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val authentication = try {
            if (!tokenProvider.validateToken(token) || !tokenProvider.isAccessToken(token)) {
                return null
            }
            tokenProvider.getAuthentication(token)
        } catch (_: RuntimeException) {
            return null
        }
        val authSessionId = (authentication.details as? JwtSessionDetails)?.sessionId ?: return null
        val memberId = authentication.principal.toString().toLongOrNull()?.takeIf { it > 0 } ?: return null
        val active = try {
            sessions.isActive(authSessionId, memberId)
        } catch (_: RuntimeException) {
            false
        }
        if (!active || !registry.bind(connectionId, BundleRealtimeIdentity(memberId, authSessionId))) {
            return null
        }
        accessor.user = authentication
        accessor.setLeaveMutable(true)
        return MessageBuilder.createMessage(message.payload, accessor.messageHeaders)
    }

    private fun authorizeExisting(
        message: Message<*>,
        accessor: StompHeaderAccessor,
        connectionId: String
    ): Message<*>? {
        // Credentials are accepted only on the native CONNECT frame. In particular, do not
        // let a later SEND replace the authenticated principal.
        if (accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION) != null) return null
        val identity = registry.identity(connectionId) ?: return null
        val authentication = accessor.user as? Authentication ?: return null
        val memberId = authentication.principal.toString().toLongOrNull() ?: return null
        val authSessionId = (authentication.details as? JwtSessionDetails)?.sessionId ?: return null
        if (memberId != identity.memberId || authSessionId != identity.authSessionId) return null
        val active = try {
            sessions.isActive(identity.authSessionId, identity.memberId)
        } catch (_: RuntimeException) {
            false
        }
        if (!active) {
            registry.remove(connectionId)
            return null
        }
        return message
    }
}
