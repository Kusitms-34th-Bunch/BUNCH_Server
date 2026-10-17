package com.bunch.global.config

import com.bunch.global.common.LoggingUtil
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID

@Component
class MDCLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(MDCLoggingFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return !path.startsWith("/api/") ||
            path.startsWith("/v3/api-docs/") ||
            path.startsWith("/swagger-ui/") ||
            path == "/swagger-ui.html"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val startTime = System.currentTimeMillis()
        val clientIp = clientIp(request)
        val logPath = if (request.method == "GET" && request.requestURI.startsWith("/api/v1/invitations/")) {
            "/api/v1/invitations/{token}"
        } else request.requestURI

        var thrown: Throwable? = null

        try {
            MDC.put("traceId", UUID.randomUUID().toString())
            MDC.put("eventId", LoggingUtil.generateEventId())
            MDC.put("clientIp", clientIp)
            refreshUserId()

            log.info("[REQ START] method={} path={} client={}", request.method, logPath, clientIp)

            chain.doFilter(request, response)
        } catch (ex: Throwable) {
            thrown = ex
            throw ex
        } finally {
            refreshUserId()

            val durationMs = System.currentTimeMillis() - startTime
            val status = if (thrown != null && response.status < 400) {
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            } else {
                response.status
            }
            val message = "[REQ END] method={} path={} status={} durationMs={} client={}"

            if (thrown != null && response.status < 400) {
                log.error(message, request.method, logPath, status, durationMs, clientIp)
            } else if (status >= 400) {
                log.warn(message, request.method, logPath, status, durationMs, clientIp)
            } else {
                log.info(message, request.method, logPath, status, durationMs, clientIp)
            }

            MDC_KEYS.forEach(MDC::remove)
        }
    }

    private fun refreshUserId() {
        val authentication = SecurityContextHolder.getContext().authentication
        val userId = authentication
            ?.takeIf { it.isAuthenticated }
            ?.takeUnless { it is AnonymousAuthenticationToken }
            ?.principal
            ?.toString()
            ?.takeUnless { it == "anonymousUser" }
            ?.takeIf { it.isNotBlank() }
            ?: GUEST_USER_ID

        MDC.put("userId", userId)
    }

    private fun clientIp(request: HttpServletRequest): String {
        val remoteAddr = request.remoteAddr

        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr
        }

        return CLIENT_IP_HEADERS.asSequence()
            .mapNotNull { request.getHeader(it)?.firstHeaderValue()?.takeIf(::isIpLiteral) }
            .firstOrNull()
            ?: request.remoteAddr
    }

    private fun String.firstHeaderValue(): String? {
        return split(",")
            .firstOrNull()
            ?.trim()
            ?.takeUnless { it.equals("unknown", ignoreCase = true) }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isTrustedProxy(remoteAddr: String?): Boolean {
        val address = parseIpLiteral(remoteAddr) ?: return false
        return address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress ||
            address.isUniqueLocalAddress()
    }

    private fun isIpLiteral(value: String): Boolean {
        return parseIpLiteral(value) != null
    }

    private fun parseIpLiteral(value: String?): InetAddress? {
        val candidate = value?.trim()?.removeSurrounding("[", "]") ?: return null
        if (candidate.isBlank() || candidate.equals("unknown", ignoreCase = true)) {
            return null
        }

        parseIpv4Literal(candidate)?.let { return it }

        if (!candidate.contains(":") || !candidate.matches(IPV6_LITERAL_CHARS)) {
            return null
        }

        return try {
            InetAddress.getByName(candidate)
        } catch (_: UnknownHostException) {
            null
        }
    }

    private fun parseIpv4Literal(candidate: String): InetAddress? {
        val parts = candidate.split(".")
        if (parts.size != 4) {
            return null
        }

        val bytes = parts.map { part ->
            if (part.isBlank() || !part.all(Char::isDigit)) {
                return null
            }

            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) {
                return null
            }

            value.toByte()
        }.toByteArray()

        return InetAddress.getByAddress(bytes)
    }

    private fun InetAddress.isUniqueLocalAddress(): Boolean {
        val firstByte = address.firstOrNull()?.toInt() ?: return false
        return firstByte and 0xFE == 0xFC
    }

    private companion object {
        const val GUEST_USER_ID = "GUEST"
        val MDC_KEYS = listOf("traceId", "eventId", "clientIp", "userId")
        val IPV6_LITERAL_CHARS = Regex("^[0-9A-Fa-f:.%]+$")
        val CLIENT_IP_HEADERS = listOf(
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR",
        )
    }
}
