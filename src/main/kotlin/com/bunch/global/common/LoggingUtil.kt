package com.bunch.global.common

import mu.KLogger
import org.apache.commons.codec.binary.Base32
import org.apache.commons.lang3.exception.ExceptionUtils
import org.slf4j.MDC
import java.nio.ByteBuffer

object LoggingUtil {

    private val base32 = Base32()

    fun generateEventId(): String {
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(System.nanoTime())
        return base32.encodeToString(buffer.array()).substring(2, 10)
    }

    fun logUnexpectedError(
        logger: KLogger,
        ex: Exception,
        param: Any? = null
    ): String {
        val eventId = MDC.get("eventId")
        val traceId = MDC.get("traceId")
        val userId = MDC.get("userId")
        val clientIp = MDC.get("clientIp")

        val root = ExceptionUtils.getRootCause(ex) ?: ex
        val stack = ex.stackTrace.firstOrNull()

        val method = if (stack != null)
            "${stack.className}.${stack.methodName}(L${stack.lineNumber})"
        else
            "Unknown"

        val logMsg = buildString {
            appendLine("[Unexpected Error]")
            appendLine("eventId=$eventId traceId=$traceId userId=$userId ip=$clientIp")
            appendLine("method=$method")
            appendLine("rootCause=$root")
            if (param != null)
                appendLine("param=$param")
        }

        logger.error(logMsg, ex)
        return eventId ?: ""
    }

    fun logBusinessError(logger: KLogger, ex: Throwable): String {
        val eventId = MDC.get("eventId")
        logger.warn("[BusinessError][$eventId] ${ex.message}")
        return eventId ?: ""
    }
}
