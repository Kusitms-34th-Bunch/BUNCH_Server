package com.bunch.global

import com.bunch.global.common.ApiResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.exception.GlobalExceptionHandler
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.*

class CommonResponseTest {
    private val mvc = MockMvcBuilders.standaloneSetup(ProbeController())
        .setControllerAdvice(GlobalExceptionHandler()).build()

    @Test
    fun `success keeps the common response shape`() {
        mvc.perform(get("/probe"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.value").value("ok"))
            .andExpect(jsonPath("$.error").isEmpty)
    }

    @Test
    fun `business failure retains its status and code`() {
        mvc.perform(get("/probe/business"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("RESOURCE_001"))
    }

    @Test
    fun `invalid JSON and blank input use distinct common errors`() {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_002"))
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"\"}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_001"))
    }

    @Test
    fun `unexpected failure does not expose its internal message`() {
        mvc.perform(get("/probe/unexpected"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error.code").value("COMMON_999"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-detail"))))
    }

    data class ProbeRequest(@field:NotBlank val value: String = "")

    @RestController
    class ProbeController {
        @GetMapping("/probe")
        fun success() = ApiResponse.ok(mapOf("value" to "ok"))
        @PostMapping("/probe")
        fun input(@Valid @RequestBody input: ProbeRequest) = ApiResponse.ok(input)
        @GetMapping("/probe/business")
        fun business(): Unit = throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        @GetMapping("/probe/unexpected")
        fun unexpected(): Unit = throw IllegalStateException("private-detail")
    }
}
