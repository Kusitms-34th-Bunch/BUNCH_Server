package com.bunch.auth.dto

import jakarta.validation.constraints.Pattern

object DevTokenDtos {
    data class Request(
        @field:Pattern(regexp = "[A-Za-z0-9_]{1,32}")
        val account: String
    )

    data class Response(val accessToken: String)
}
