package com.bunch.global.security

import com.bunch.global.common.ApiResponse
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.dao.DataAccessException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val tokenProvider: TokenProvider,
    private val sessions: RefreshTokenRepository,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val bearer = request.getHeader("Authorization")
        val token = bearer
            ?.takeIf { it.startsWith("Bearer ") }
            ?.substring(7)
        if (token != null) {
            val authentication = try {
                if (
                    tokenProvider.validateToken(token) &&
                        tokenProvider.isAccessToken(token)
                ) {
                    tokenProvider.getAuthentication(token)
                } else null
            } catch (_: RuntimeException) {
                null
            }
            if (authentication == null) {
                SecurityContextHolder.clearContext()
            } else {
                val sessionId = (
                    authentication.details as JwtSessionDetails
                ).sessionId
                val active = try {
                    sessions.isActive(
                        sessionId,
                        authentication.principal.toString().toLong()
                    )
                } catch (_: DataAccessException) {
                    SecurityContextHolder.clearContext()
                    val error = ErrorCode.AUTH_SESSION_UNAVAILABLE
                    response.status = error.status
                    response.contentType = "application/json;charset=UTF-8"
                    objectMapper.writeValue(
                        response.writer,
                        ApiResponse.fail(error.code, error.messageKey)
                    )
                    return
                }
                if (active) {
                    SecurityContextHolder.getContext().authentication = authentication
                    if (MDC.get("traceId") != null) {
                        MDC.put("userId", authentication.principal.toString())
                    }
                } else {
                    SecurityContextHolder.clearContext()
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}
