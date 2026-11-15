package com.bunch.bundle

import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.service.BundleRealtimeAuthInterceptor
import com.bunch.bundle.service.BundleRealtimeSessionRegistry
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.JwtSessionDetails
import com.bunch.global.security.TokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication

class BundleRealtimeAuthInterceptorTest {
    private val tokens = mock(TokenProvider::class.java)
    private val sessions = mock(RefreshTokenRepository::class.java)
    private val registry = BundleRealtimeSessionRegistry()
    private val channel = mock(MessageChannel::class.java)
    private val interceptor = BundleRealtimeAuthInterceptor(tokens, sessions, registry)
    private val authentication = UsernamePasswordAuthenticationToken(MEMBER_ID.toString(), null, emptyList()).apply {
        details = JwtSessionDetails(SESSION_ID)
    }

    @Test
    fun `CONNECT authenticates only once and later SEND and SUBSCRIBE survive token expiry`() {
        `when`(tokens.getConnectAuthentication(WS_TOKEN))
            .thenReturn(authentication)
            .thenThrow(IllegalArgumentException("WS token expired after CONNECT"))
        `when`(sessions.isActive(SESSION_ID, MEMBER_ID)).thenReturn(true)

        val connected = interceptor.preSend(frame(StompCommand.CONNECT, token = WS_TOKEN), channel)
        assertThat(connected).isNotNull()
        val connectedUser = StompHeaderAccessor.wrap(connected!!).user as Authentication
        assertThat(registry.identity(CONNECTION_ID)).isEqualTo(BundleActor(MEMBER_ID, SESSION_ID))

        // A second JWT verification would now fail. Existing frames must use the bound
        // principal and current Redis session instead, regardless of the JWT deadline.
        listOf(StompCommand.SEND, StompCommand.SUBSCRIBE).forEach { command ->
            val message = frame(command, user = connectedUser)
            assertThat(interceptor.preSend(message, channel)).isSameAs(message)
        }

        verify(tokens).getConnectAuthentication(WS_TOKEN)
        verifyNoMoreInteractions(tokens)
        verify(sessions, times(3)).isActive(SESSION_ID, MEMBER_ID)
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand::class, names = ["SEND", "SUBSCRIBE"])
    fun `Redis revocation rejects later frame and reconnect without relying on JWT expiry`(command: StompCommand) {
        `when`(tokens.getConnectAuthentication(WS_TOKEN)).thenReturn(authentication)
        `when`(sessions.isActive(SESSION_ID, MEMBER_ID)).thenReturn(true, false, false)
        assertThat(interceptor.preSend(frame(StompCommand.CONNECT, token = WS_TOKEN), channel)).isNotNull()

        assertThat(interceptor.preSend(frame(command, user = authentication), channel)).isNull()
        assertThat(registry.identity(CONNECTION_ID)).isNull()
        assertThat(interceptor.preSend(
            frame(StompCommand.CONNECT, token = WS_TOKEN, connectionId = "reconnection"), channel
        )).isNull()
        assertThat(registry.identity("reconnection")).isNull()

        verify(tokens, times(2)).getConnectAuthentication(WS_TOKEN)
        verifyNoMoreInteractions(tokens)
        verify(sessions, times(3)).isActive(SESSION_ID, MEMBER_ID)
    }

    @Test
    fun `Authorization on a later SEND cannot replace the CONNECT identity`() {
        `when`(tokens.getConnectAuthentication(WS_TOKEN)).thenReturn(authentication)
        `when`(sessions.isActive(SESSION_ID, MEMBER_ID)).thenReturn(true)
        assertThat(interceptor.preSend(frame(StompCommand.CONNECT, token = WS_TOKEN), channel)).isNotNull()

        val replacement = frame(StompCommand.SEND, user = authentication, token = "replacement-token")
        assertThat(interceptor.preSend(replacement, channel)).isNull()
        assertThat(registry.identity(CONNECTION_ID)).isEqualTo(BundleActor(MEMBER_ID, SESSION_ID))

        verify(tokens).getConnectAuthentication(WS_TOKEN)
        verifyNoMoreInteractions(tokens)
        verify(sessions).isActive(SESSION_ID, MEMBER_ID)
        verifyNoMoreInteractions(sessions)
    }

    private fun frame(
        command: StompCommand,
        user: Authentication? = null,
        token: String? = null,
        connectionId: String = CONNECTION_ID
    ): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(command)
        accessor.sessionId = connectionId
        accessor.user = user
        if (token != null) accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
        if (command == StompCommand.SEND || command == StompCommand.SUBSCRIBE) {
            accessor.destination = "/app/bunches/1/bundles/1/presence"
        }
        accessor.setLeaveMutable(true)
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    private companion object {
        const val MEMBER_ID = 201L
        const val SESSION_ID = "0fa7fa02-e88b-4a36-a293-b301722d3e0b"
        const val CONNECTION_ID = "ws-connection"
        const val WS_TOKEN = "test-ws-connect-token"
    }
}
