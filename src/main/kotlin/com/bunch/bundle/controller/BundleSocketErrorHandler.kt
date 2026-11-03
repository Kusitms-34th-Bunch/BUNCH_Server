package com.bunch.bundle.controller

import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.dto.BundleCommandRejectedData
import com.bunch.bundle.dto.BundleRealtimeMessage
import com.bunch.global.exception.BusinessException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler

class BundleSocketErrorHandler : StompSubProtocolErrorHandler() {
    override fun handleClientMessageProcessingError(
        clientMessage: Message<ByteArray>?,
        ex: Throwable
    ): Message<ByteArray> {
        val sessionId = clientMessage
            ?.let { StompHeaderAccessor.wrap(it).sessionId }
        val code = findBusinessException(ex)?.errorCode?.code ?: "WS_001"
        return sanitized(sessionId, code)
    }

    override fun handleErrorMessageToClient(errorMessage: Message<ByteArray>): Message<ByteArray> =
        sanitized(StompHeaderAccessor.wrap(errorMessage).sessionId, "WS_001")

    private fun sanitized(sessionId: String?, code: String): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(StompCommand.ERROR)
        accessor.sessionId = sessionId
        accessor.message = "WebSocket command rejected"
        val payload = jacksonObjectMapper().writeValueAsBytes(
            BundleRealtimeMessage(
                type = BundleEventType.COMMAND_REJECTED,
                bunchId = null,
                bundleId = null,
                data = BundleCommandRejectedData(code)
            )
        )
        return MessageBuilder.createMessage(payload, accessor.messageHeaders)
    }

    private fun findBusinessException(error: Throwable): BusinessException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<BusinessException>()
            .firstOrNull()
}
