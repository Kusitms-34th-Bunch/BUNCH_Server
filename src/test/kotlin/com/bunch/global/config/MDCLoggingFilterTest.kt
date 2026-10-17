package com.bunch.global.config

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.MDC
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@ExtendWith(OutputCaptureExtension::class)
class MDCLoggingFilterTest {

    private val filter = MDCLoggingFilter()

    @Test
    fun `thrown chain logs status 500 at error level rethrows and removes own mdc keys`(
        output: CapturedOutput
    ) {
        val request = MockHttpServletRequest("GET", "/api/v1/test/boom").apply {
            remoteAddr = "127.0.0.1"
        }
        val response = MockHttpServletResponse()
        val failure = IllegalStateException("boom")
        val chain = FilterChain { _, _ -> throw failure }

        MDC.put("unrelated", "kept")
        try {
            assertThatThrownBy {
                filter.doFilter(request, response, chain)
            }.isSameAs(failure)

            val logs = output.out + output.err
            assertThat(logs)
                .contains("[REQ START]")
                .contains("[REQ END]")
                .contains("status=500")
                .contains("ERROR")

            assertThat(MDC.get("traceId")).isNull()
            assertThat(MDC.get("eventId")).isNull()
            assertThat(MDC.get("clientIp")).isNull()
            assertThat(MDC.get("userId")).isNull()
            assertThat(MDC.get("unrelated")).isEqualTo("kept")
        } finally {
            MDC.remove("unrelated")
        }
    }

    @Test
    fun `thrown chain after error response logs existing status at warn level and rethrows`(
        output: CapturedOutput
    ) {
        val request = MockHttpServletRequest("GET", "/api/v1/test/forbidden").apply {
            remoteAddr = "127.0.0.1"
        }
        val response = MockHttpServletResponse()
        val failure = IllegalArgumentException("forbidden")
        val chain = FilterChain { _, servletResponse ->
            servletResponse as MockHttpServletResponse
            servletResponse.status = 403
            throw failure
        }

        assertThatThrownBy {
            filter.doFilter(request, response, chain)
        }.isSameAs(failure)

        val logs = output.out + output.err
        assertThat(logs)
            .contains("[REQ START]")
            .contains("[REQ END]")
            .contains("status=403")
            .contains("WARN")
            .doesNotContain("status=500")
            .doesNotContain("ERROR")
    }
}
