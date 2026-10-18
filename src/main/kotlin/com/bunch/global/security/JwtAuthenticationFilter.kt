package com.bunch.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(private val tokenProvider: TokenProvider) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val bearer = request.getHeader("Authorization")
        val token = bearer?.takeIf { it.startsWith("Bearer ") }?.substring(7)
        if (token != null) {
            try {
                if (tokenProvider.validateToken(token) && tokenProvider.isAccessToken(token)) {
                    SecurityContextHolder.getContext().authentication = tokenProvider.getAuthentication(token)
                }
            } catch (_: RuntimeException) {
                SecurityContextHolder.clearContext()
            }
        }
        filterChain.doFilter(request, response)
    }
}
