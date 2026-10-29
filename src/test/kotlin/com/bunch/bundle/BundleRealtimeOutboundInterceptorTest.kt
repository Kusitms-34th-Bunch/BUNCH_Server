package com.bunch.bundle

import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.service.BundleAccessService
import com.bunch.bundle.service.BundleRealtimeDestinations
import com.bunch.bundle.service.BundleRealtimeIdentity
import com.bunch.bundle.service.BundleRealtimeOutboundInterceptor
import com.bunch.bundle.service.BundleRealtimeSessionRegistry
import com.bunch.bundle.service.BundleRealtimeSubscription
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder

class BundleRealtimeOutboundInterceptorTest {
    private val sessions = mock(RefreshTokenRepository::class.java)
    private val access = mock(BundleAccessService::class.java)
    private val registry = BundleRealtimeSessionRegistry()
    private val mapper = ObjectMapper()
    private val interceptor = BundleRealtimeOutboundInterceptor(sessions, access, registry, mapper)
    private val channel = mock(MessageChannel::class.java)

    @Test
    fun `revoked redis session drops outbound topic message before access check`() {
        bindConnection()
        `when`(sessions.isActive(AUTH_SESSION_ID, MEMBER_ID)).thenReturn(false)

        val message = topicMessage(type = "TITLE")

        assertThat(interceptor.preSend(message, channel)).isNull()
        verify(sessions).isActive(AUTH_SESSION_ID, MEMBER_ID)
        verifyNoInteractions(access)
        assertThat(registry.identity(CONNECTION_ID)).isNull()
    }

    @Test
    fun `lost membership drops a live topic event`() {
        bindConnection()
        subscribeToTopic()
        `when`(sessions.isActive(AUTH_SESSION_ID, MEMBER_ID)).thenReturn(true)
        doThrow(BusinessException(ErrorCode.FORBIDDEN))
            .`when`(access)
            .read(MEMBER_ID, BUNCH_ID, BUNDLE_ID)

        val message = topicMessage(type = "TITLE")

        assertThat(interceptor.preSend(message, channel)).isNull()
        verify(access).read(MEMBER_ID, BUNCH_ID, BUNDLE_ID)
    }

    @Test
    fun `active member receives a topic event without public auth session id`() {
        bindConnection()
        subscribeToTopic()
        `when`(sessions.isActive(AUTH_SESSION_ID, MEMBER_ID)).thenReturn(true)
        `when`(access.read(MEMBER_ID, BUNCH_ID, BUNDLE_ID))
            .thenReturn(Bundle(id = BUNDLE_ID, bunchId = BUNCH_ID, createdBy = MEMBER_ID))

        val message = topicMessage(type = "TITLE")

        assertThat(interceptor.preSend(message, channel)).isSameAs(message)
        assertThat(message.payload).doesNotContain(AUTH_SESSION_ID)
        verify(access).read(MEMBER_ID, BUNCH_ID, BUNDLE_ID)
    }

    @Test
    fun `deleted topic event checks bunch membership after bundle is gone`() {
        bindConnection()
        subscribeToTopic()
        `when`(sessions.isActive(AUTH_SESSION_ID, MEMBER_ID)).thenReturn(true)
        doThrow(BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
            .`when`(access)
            .read(MEMBER_ID, BUNCH_ID, BUNDLE_ID)

        val message = topicMessage(type = "DELETED")

        assertThat(interceptor.preSend(message, channel)).isSameAs(message)
        verify(access).requireMember(MEMBER_ID, BUNCH_ID)
        verify(access, never()).read(MEMBER_ID, BUNCH_ID, BUNDLE_ID)
    }

    @Test
    fun `private lease payload is delivered only to the owning subscribed connection`() {
        bindConnection()
        `when`(sessions.isActive(AUTH_SESSION_ID, MEMBER_ID)).thenReturn(true)
        val message = privateMessage()

        assertThat(registry.bind("other-connection", BundleRealtimeIdentity(MEMBER_ID, "other-auth-session"))).isTrue()
        assertThat(
            registry.addSubscription(
                "other-connection",
                BundleRealtimeSubscription("other-lease-subscription", BundleRealtimeDestinations.LEASE_QUEUE)
            )
        ).isTrue()
        assertThat(interceptor.preSend(message, channel)).isNull()
        verifyNoInteractions(access)

        registry.addSubscription(
            CONNECTION_ID,
            BundleRealtimeSubscription("lease-subscription", BundleRealtimeDestinations.LEASE_QUEUE)
        )
        `when`(access.read(MEMBER_ID, BUNCH_ID, BUNDLE_ID))
            .thenReturn(Bundle(id = BUNDLE_ID, bunchId = BUNCH_ID, createdBy = MEMBER_ID))

        assertThat(interceptor.preSend(message, channel)).isSameAs(message)
        assertThat(message.payload).doesNotContain(AUTH_SESSION_ID)
        verify(access).read(MEMBER_ID, BUNCH_ID, BUNDLE_ID)
    }

    private fun bindConnection() {
        assertThat(registry.bind(CONNECTION_ID, BundleRealtimeIdentity(MEMBER_ID, AUTH_SESSION_ID))).isTrue()
    }

    private fun subscribeToTopic() {
        assertThat(
            registry.addSubscription(
                CONNECTION_ID,
                BundleRealtimeSubscription(
                    id = "topic-subscription",
                    destination = TOPIC,
                    bunchId = BUNCH_ID,
                    bundleId = BUNDLE_ID
                )
            )
        ).isTrue()
    }

    private fun topicMessage(type: String): Message<String> = brokerMessage(
        destination = TOPIC,
        payload = """
            {
              "bunchId": $BUNCH_ID,
              "bundleId": $BUNDLE_ID,
              "type": "$type"
            }
        """.trimIndent()
    )

    private fun privateMessage(): Message<String> = brokerMessage(
        destination = BundleRealtimeDestinations.LEASE_QUEUE,
        payload = """
            {
              "bunchId": $BUNCH_ID,
              "bundleId": $BUNDLE_ID,
              "token": "private-lease-token"
            }
        """.trimIndent()
    )

    private fun brokerMessage(destination: String, payload: String): Message<String> {
        val accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE)
        accessor.setSessionId(CONNECTION_ID)
        accessor.setDestination(destination)
        accessor.setLeaveMutable(true)
        val message = MessageBuilder.createMessage(payload, accessor.messageHeaders)
        assertThat(StompHeaderAccessor.wrap(message).command).isNull()
        return message
    }

    private companion object {
        const val MEMBER_ID = 201L
        const val BUNCH_ID = 42L
        const val BUNDLE_ID = 7L
        const val CONNECTION_ID = "connection-201"
        const val AUTH_SESSION_ID = "auth-session-201"
        val TOPIC = BundleRealtimeDestinations.topic(BUNCH_ID, BUNDLE_ID)
    }
}
