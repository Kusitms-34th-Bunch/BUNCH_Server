package com.bunch.global.profanity

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Immutable matching snapshot. One operation can inspect several fields using the same policy. */
class ProfanityDictionary(
    base: List<String>,
    additions: List<String> = emptyList(),
    exclusions: List<String> = emptyList()
) {
    private val originalWords: List<String>
    private val compactWords: List<String>
    val version: String

    init {
        val excluded = exclusions.map(::identity).toSet()
        val words = (base + additions).map(::normalize).onEach {
            require(it.isNotBlank()) { "Dictionary entries must not be blank" }
        }.filterNot { identity(it) in excluded }
        originalWords = words.distinct().sorted()
        compactWords = words.map(::compact).filter { it.isNotEmpty() }.distinct().sorted()
        val canonical = (originalWords.map { "original:${it.length}:$it" } +
            compactWords.map { "compact:${it.length}:$it" }).joinToString("\n")
        version = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun contains(value: String): Boolean {
        val normalized = normalize(value)
        if (originalWords.any(normalized::contains)) return true
        val stripped = compact(normalized)
        return compactWords.any(stripped::contains)
    }

    fun requireClean(fields: Map<String, String>) {
        val rejected = fields.filterValues(::contains).keys.toList()
        if (rejected.isNotEmpty()) {
            throw BusinessException(ErrorCode.PROHIBITED_WORD, mapOf("fields" to rejected))
        }
    }

    private fun identity(value: String): String = normalize(value).let { normalized ->
        compact(normalized).ifEmpty { normalized }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun compact(value: String): String {
        val result = StringBuilder()
        value.codePoints().forEach { point ->
            val type = Character.getType(point)
            val variationSelector = point in 0xFE00..0xFE0F || point in 0xE0100..0xE01EF
            if (!variationSelector && (letterOrNumber(point) || type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt())) {
                result.appendCodePoint(point)
            }
        }
        val normalized = Normalizer.normalize(result, Normalizer.Form.NFC)
        return normalized.takeIf { text -> text.codePoints().anyMatch(::letterOrNumber) }.orEmpty()
    }

    private fun letterOrNumber(point: Int): Boolean = Character.isLetterOrDigit(point) ||
        Character.getType(point) == Character.LETTER_NUMBER.toInt() ||
        Character.getType(point) == Character.OTHER_NUMBER.toInt()
}
