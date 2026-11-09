package com.bunch.auth.controller

import com.bunch.auth.dto.DevTokenDtos
import com.bunch.auth.service.DevTokenService
import com.bunch.global.common.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth/dev")
class DevTokenController(private val tokens: DevTokenService) {
    @PostMapping("/token")
    fun issue(
        @Valid
        @RequestBody
        request: DevTokenDtos.Request
    ) = ApiResponse.ok(DevTokenDtos.Response(tokens.issue(request.account)))
}
