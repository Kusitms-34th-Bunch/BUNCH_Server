package com.bunch.bundle.domain

import java.text.Normalizer
import java.util.regex.Pattern

object BundleText {
    private const val TITLE_MAX_GRAPHEMES = 40
    private const val COMMENT_MAX_GRAPHEMES = 400

    private val graphemePattern = Pattern.compile("\\X")
    private val lineBreakPattern = Regex("[\\r\\n\\u0085\\u2028\\u2029]")

    fun title(value: String): String {
        val normalized = normalize(value)
        require(!lineBreakPattern.containsMatchIn(normalized)) {
            "title must not contain line breaks"
        }

        return normalized.trim().also {
            require(graphemeLength(it) <= TITLE_MAX_GRAPHEMES) {
                "title must be at most $TITLE_MAX_GRAPHEMES graphemes"
            }
        }
    }

    fun mergedTitle(value: String): String {
        val normalized = normalize(value)
        require(!lineBreakPattern.containsMatchIn(normalized) && '\u0000' !in normalized)
        return normalized.trim()
    }

    fun titleTooLong(value: String): Boolean = graphemeLength(normalize(value).trim()) > TITLE_MAX_GRAPHEMES

    fun comment(value: String): String = validate(value, COMMENT_MAX_GRAPHEMES, "comment")

    private fun validate(value: String, maximum: Int, field: String): String = normalize(value).also {
        require(graphemeLength(it) <= maximum) {
            "$field must be at most $maximum graphemes"
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

    private fun graphemeLength(value: String): Int =
        graphemePattern.matcher(value).results().count().toInt()
}
