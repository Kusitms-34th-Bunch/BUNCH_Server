package com.bunch.bundle.controller

import com.bunch.bundle.service.BundleRealtimeAuthInterceptor
import com.bunch.bundle.service.BundleRealtimeAuthorizationInterceptor
import com.bunch.bundle.service.BundleRealtimeOutboundInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
class BundleSocketConfig(
    private val authInterceptor: BundleRealtimeAuthInterceptor,
    private val authorizationInterceptor: BundleRealtimeAuthorizationInterceptor,
    private val outboundInterceptor: BundleRealtimeOutboundInterceptor,
    @Value("\${bunch.websocket.allowed-origins:http://localhost:3000}")
    private val configuredOrigins: String
) : WebSocketMessageBrokerConfigurer {
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.setPreserveReceiveOrder(true)
        registry.setErrorHandler(BundleSocketErrorHandler())
            .addEndpoint("/ws")
            .addInterceptors(BundleSocketHandshakeInterceptor())
            .setAllowedOrigins(*allowedOrigins())
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic", "/queue")
        registry.setPreservePublishOrder(true)
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(authInterceptor, authorizationInterceptor)
    }

    override fun configureClientOutboundChannel(registration: ChannelRegistration) {
        registration.interceptors(outboundInterceptor)
    }

    private fun allowedOrigins(): Array<String> = configuredOrigins
        .split(',')
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.contains('*') }
        .ifEmpty { listOf("http://localhost:3000") }
        .toTypedArray()
}
