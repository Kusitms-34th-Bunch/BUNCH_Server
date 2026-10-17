package com.bunch.global.exception

import com.bunch.global.common.ApiResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun business(e: BusinessException) = ResponseEntity.status(e.errorCode.status)
        .body(ApiResponse.fail(e.errorCode.code, e.customMessage ?: e.errorCode.messageKey, e.detail))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException) = ResponseEntity.badRequest().body(
        ApiResponse.fail(
            ErrorCode.INVALID_INPUT.code,
            ErrorCode.INVALID_INPUT.messageKey,
            e.bindingResult.fieldErrors.firstOrNull()?.let {
                mapOf("field" to it.field, "reason" to it.defaultMessage)
            }
        )
    )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun json(e: HttpMessageNotReadableException) = business(BusinessException(ErrorCode.INVALID_JSON))

    @ExceptionHandler(
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class,
        org.springframework.web.bind.MissingServletRequestParameterException::class,
        org.springframework.web.bind.MissingRequestHeaderException::class,
        org.springframework.web.multipart.support.MissingServletRequestPartException::class,
        org.springframework.web.multipart.MaxUploadSizeExceededException::class
    )
    fun badRequest(e: Exception) = business(BusinessException(ErrorCode.INVALID_INPUT))

    @ExceptionHandler(NoResourceFoundException::class)
    fun missing(e: NoResourceFoundException) = business(BusinessException(ErrorCode.RESOURCE_NOT_FOUND))

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiResponse<Unit>> {
        logger.error("Unexpected failure type={} eventId={}", e.javaClass.simpleName, MDC.get("eventId"))
        return business(BusinessException(ErrorCode.INTERNAL_ERROR, mapOf("eventId" to MDC.get("eventId"))))
    }
}
