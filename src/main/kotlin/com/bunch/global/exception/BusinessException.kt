package com.bunch.global.exception

open class BusinessException(
    val errorCode: ErrorCode,
    val detail: Any? = null,
    val customMessage: String? = null
) : RuntimeException(customMessage ?: errorCode.messageKey)
