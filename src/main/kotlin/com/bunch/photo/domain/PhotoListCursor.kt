package com.bunch.photo.domain

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64

/**
 * A position in the photo box order: newest date first, then the later time, then the lower ID.
 * A photo without a time comes last in its day. The client hands the encoded value back unchanged.
 */
data class PhotoListCursor(val date: LocalDate, val time: LocalTime?, val id: Long) {
    fun encode(): String {
        val text = "$date$SEPARATOR${time ?: ""}$SEPARATOR$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    }

    companion object {
        private const val SEPARATOR = "|"
        private const val MAX_LENGTH = 120

        /** Anything that is not a value produced by [encode] is invalid input. */
        fun decode(value: String): PhotoListCursor {
            try {
                require(value.length <= MAX_LENGTH)
                val parts = String(Base64.getUrlDecoder().decode(value)).split(SEPARATOR)
                require(parts.size == 3)
                val id = parts[2].toLong()
                require(id > 0)
                return PhotoListCursor(
                    LocalDate.parse(parts[0]),
                    parts[1].takeIf { it.isNotEmpty() }?.let(LocalTime::parse),
                    id
                )
            } catch (_: RuntimeException) {
                throw BusinessException(ErrorCode.INVALID_INPUT)
            }
        }
    }
}
