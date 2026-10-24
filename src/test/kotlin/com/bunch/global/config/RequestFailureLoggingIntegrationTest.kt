package com.bunch.global.config

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RequestFailureLoggingIntegrationTest.Endpoints::class)
@ExtendWith(OutputCaptureExtension::class)
class RequestFailureLoggingIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val tokens: TokenProvider
) {
    @MockitoBean
    private lateinit var sessions: RefreshTokenRepository

    @Test
    fun `Redis failure before controller is logged as a 503 without credentials`(output: CapturedOutput) {
        val token = tokens.generateToken(42, "ROLE_USER").accessToken
        `when`(sessions.isActive(anyString(), anyLong()))
            .thenThrow(DataAccessResourceFailureException("private connection details"))

        mvc.perform(get(PATH).header("Authorization", "Bearer $token"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error.code").value("AUTH_018"))

        assertThat(output.all)
            .contains("[REQ END] method=GET path=$PATH status=503")
            .doesNotContain(token, "private connection details")
        assertThat(MDC.get("traceId")).isNull()
        assertThat(MDC.get("eventId")).isNull()
    }

    @Test
    fun `handled controller failure logs one completion and keeps response event id`(output: CapturedOutput) {
        val token = tokens.generateToken(42, "ROLE_USER").accessToken
        `when`(sessions.isActive(anyString(), anyLong())).thenReturn(true)

        mvc.perform(get(PATH).header("Authorization", "Bearer $token"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error.detail.eventId").isNotEmpty)

        val completion = "[REQ END] method=GET path=$PATH status=500"
        assertThat(output.all.split(completion)).hasSize(2)
        assertThat(output.all)
            .containsPattern("\\[userId=42\\].*Unexpected failure type=IllegalStateException")
            .doesNotContain(token, "private failure details")
        assertThat(MDC.get("userId")).isNull()
    }

    @RestController
    @TestComponent
    class Endpoints {
        @GetMapping(PATH)
        fun fail(): Unit = throw IllegalStateException("private failure details")
    }

    companion object {
        const val PATH = "/api/v1/monitoring-test/failure"
    }
}
