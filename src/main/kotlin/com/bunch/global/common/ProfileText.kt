package com.bunch.global.common

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import java.text.Normalizer
import java.util.regex.Pattern

object ProfileText {
    private val grapheme = Pattern.compile("\\X")
    private val nameCharacters = Regex("[\\p{IsHangul}A-Za-z0-9 ]+")

    fun name(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).also {
        if (it.codePointCount(0, it.length) !in 1..12 || !nameCharacters.matches(it)) invalid()
    }

    fun introduction(value: String): String = value.trim().also {
        if (it.isBlank() || length(it) !in 1..50) invalid()
    }

    private fun length(value: String): Int = grapheme.matcher(value).results().count().toInt()
    private fun invalid(): Nothing = throw BusinessException(ErrorCode.INVALID_INPUT)
}
