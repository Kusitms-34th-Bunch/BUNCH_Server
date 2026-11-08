package com.bunch.social.domain

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import java.util.regex.Pattern

object SocialCommentText {
    private val grapheme = Pattern.compile("\\X")

    fun content(value: String): String = value.trim().also {
        if (it.isBlank() || grapheme.matcher(it).results().count() !in 1L..200L) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
    }
}
