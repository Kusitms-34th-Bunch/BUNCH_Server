package com.bunch.photo.domain

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import java.util.Base64

/**
 * A position in the list of photos without a capture date, newest upload first. The photos have no date or time to
 * order by, so the ID of the last photo seen is the whole position. The client hands the encoded value back unchanged.
 */
data class PhotoUndatedCursor(val id: Long) {
    fun encode(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$PREFIX$SEPARATOR$id".toByteArray())

    companion object {
        // The prefix keeps a cursor of the photo box list from being read as this one.
        private const val PREFIX = "u"
        private const val SEPARATOR = "|"
        private const val MAX_LENGTH = 120

        /** No photo has a larger ID, so the photos before it are the first page. It stands in for "no cursor". */
        val FIRST = PhotoUndatedCursor(Long.MAX_VALUE)

        /** Anything that is not a value produced by [encode] is invalid input. */
        fun decode(value: String): PhotoUndatedCursor {
            try {
                require(value.length <= MAX_LENGTH)
                val parts = String(Base64.getUrlDecoder().decode(value)).split(SEPARATOR)
                require(parts.size == 2 && parts[0] == PREFIX)
                val id = parts[1].toLong()
                require(id > 0)
                return PhotoUndatedCursor(id)
            } catch (_: RuntimeException) {
                throw BusinessException(ErrorCode.INVALID_INPUT)
            }
        }
    }
}
