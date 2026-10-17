package com.bunch.auth.security


import com.bunch.global.common.ApiResponse
import com.bunch.global.exception.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler


import org.springframework.stereotype.Component

@Component
class CustomAccessDeniedHandler : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException
    ) {

        val body = ApiResponse.fail(
            code = ErrorCode.FORBIDDEN.code,        // Forbidden 코드
            messageKey = ErrorCode.FORBIDDEN.messageKey,
            detail = "You do not have permission to access this resource"
        )

        response.status = ErrorCode.FORBIDDEN.status
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write(body.toJson())
    }
}
