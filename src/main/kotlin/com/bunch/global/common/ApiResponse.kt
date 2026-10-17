package com.bunch.global.common

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> =
            ApiResponse(success = true, data = data)

        fun <T> empty(@Suppress("UNUSED_PARAMETER") data: T): ApiResponse<T> =
            ApiResponse(success = true, data = null)

        fun fail(
            code: String,
            messageKey: String,
            detail: Any? = null
        ): ApiResponse<Unit> =
            ApiResponse(
                success = false,
                error = ApiError(code, messageKey, detail)
            )
    }
}

data class ApiError(
    val code: String,
    val message: String,
    val detail: Any? = null
)
