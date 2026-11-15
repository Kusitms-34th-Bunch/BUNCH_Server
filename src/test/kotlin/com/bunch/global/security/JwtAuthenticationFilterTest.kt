package com.bunch.global.security

import com.bunch.global.redis.RefreshTokenRepository
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import java.util.Date
import java.util.UUID
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.slf4j.MDC
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.Base64

class JwtAuthenticationFilterTest {
    private val tokens = TokenProvider(Base64.getEncoder().encodeToString(ByteArray(64) { 7 }))
    private val sessions = mock(RefreshTokenRepository::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val filter = JwtAuthenticationFilter(tokens, sessions, objectMapper)
    private val chain = mock(FilterChain::class.java)
    private val request = MockHttpServletRequest()
    private val response = MockHttpServletResponse()

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
        MDC.clear()
    }

    @Test
    fun `WS bearer cannot reach even a public token issuing route`() {
        for (expired in listOf(false, true)) {
            val issuedAt = Date(System.currentTimeMillis() - if (expired) 120_000 else 10_000)
            val token = Jwts.builder().setSubject("7").claim("sid", UUID.randomUUID().toString())
                .claim("tokenType", "ws").setAudience("bunch-ws").setId(UUID.randomUUID().toString())
                .setIssuedAt(issuedAt).setExpiration(Date(issuedAt.time + 60_000))
                .signWith(Keys.hmacShaKeyFor(ByteArray(64) { 7 }), SignatureAlgorithm.HS512).compact()
            val wsRequest = MockHttpServletRequest("POST", "/api/v1/auth/dev/token")
            wsRequest.addHeader("Authorization", "Bearer $token")
            val wsResponse = MockHttpServletResponse()

            filter.doFilter(wsRequest, wsResponse, chain)

            assertEquals(401, wsResponse.status)
            assertNull(SecurityContextHolder.getContext().authentication)
            assertEquals("AUTH_001", objectMapper.readTree(wsResponse.contentAsString).path("error").path("code").asText())
        }
        verifyNoInteractions(sessions, chain)
    }

    @Test
    fun `valid access token and live session authenticate before continuing chain`() {
        val pair = tokens.generateToken(7, "ROLE_USER")
        val sid = tokens.getSessionId(pair.accessToken)
        request.addHeader("Authorization", "Bearer ${pair.accessToken}")
        `when`(sessions.isActive(sid, 7)).thenReturn(true)

        filter.doFilter(request, response, chain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertEquals("7", authentication.principal)
        assertEquals(sid, (authentication.details as JwtSessionDetails).sessionId)
        verify(sessions).isActive(sid, 7)
        verify(chain).doFilter(request, response)
        assertEquals(200, response.status)
        assertNull(MDC.get("userId"))
    }

    @Test
    fun `authentication updates the request user before downstream logging`() {
        val pair = tokens.generateToken(7, "ROLE_USER")
        val sid = tokens.getSessionId(pair.accessToken)
        request.addHeader("Authorization", "Bearer ${pair.accessToken}")
        `when`(sessions.isActive(sid, 7)).thenReturn(true)
        MDC.put("traceId", "request-scope")
        MDC.put("userId", "GUEST")
        doAnswer {
            assertEquals("7", MDC.get("userId"))
            null
        }.`when`(chain).doFilter(request, response)

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun `absent session leaves request unauthenticated for security chain rejection`() {
        val pair = tokens.generateToken(7, "ROLE_USER")
        val sid = tokens.getSessionId(pair.accessToken)
        request.addHeader("Authorization", "Bearer ${pair.accessToken}")
        `when`(sessions.isActive(sid, 7)).thenReturn(false)

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(sessions).isActive(sid, 7)
        verify(chain).doFilter(request, response)
        assertTrue(response.contentAsString.isEmpty())
    }

    @Test
    fun `malformed JWT remains unauthenticated without consulting Redis`() {
        request.addHeader("Authorization", "Bearer malformed")

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verifyNoInteractions(sessions)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `Redis outage returns service unavailable envelope and stops chain without leaking exception`() {
        val pair = tokens.generateToken(7, "ROLE_USER")
        val sid = tokens.getSessionId(pair.accessToken)
        request.addHeader("Authorization", "Bearer ${pair.accessToken}")
        `when`(sessions.isActive(sid, 7)).thenThrow(DataAccessResourceFailureException("private connection details"))

        filter.doFilter(request, response, chain)

        assertEquals(503, response.status)
        assertEquals("application/json;charset=UTF-8", response.contentType)
        assertEquals(objectMapper.readTree("""{"success":false,"data":null,"error":{"code":"AUTH_018","message":"error.auth_session_unavailable","detail":null}}"""),
            objectMapper.readTree(response.contentAsString))
        assertNull(SecurityContextHolder.getContext().authentication)
        verify(sessions).isActive(sid, 7)
        verifyNoInteractions(chain)
    }
}
