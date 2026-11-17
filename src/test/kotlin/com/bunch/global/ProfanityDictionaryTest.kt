package com.bunch.global

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.profanity.ProfanityDictionary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.text.Normalizer

class ProfanityDictionaryTest {
    private val dictionary = ProfanityDictionary(listOf("씨발", "fuck", "ㅅㅂ", "🖕"))

    @Test
    fun `matches canonical Korean English case and separated literal words`() {
        listOf(
            "씨발", Normalizer.normalize("씨발", Normalizer.Form.NFD), "씨 발", "씨.발", "씨💛발", "씨❤️발",
            "씨\u200B발", "씨\u00A0발", "씨\t발", "FUCK", "f.u.c.k", "f u c k", "ㅅ.ㅂ", "🖕"
        ).forEach { assertThat(dictionary.contains(it)).describedAs(it).isTrue() }
        listOf("즐거운 여행", "평범한 기록", "ㅆ ㅣ ㅂ ㅏ ㄹ", "😀", "", " ... ")
            .forEach { assertThat(dictionary.contains(it)).describedAs(it).isFalse() }
    }

    @Test
    fun `normalizes again after removing a separator between canonical jamo`() {
        assertThat(ProfanityDictionary(listOf("시발")).contains("ᄉ.ᅵ발")).isTrue()
    }

    @Test
    fun `exclusions remove dictionary items but never exempt a whole sentence`() {
        val custom = ProfanityDictionary(listOf("시발", "씨.발", "fuck"), listOf("새금칙어"), listOf("시 발", "씨발"))
        assertThat(custom.contains("시발점")).isFalse()
        assertThat(custom.contains("씨발")).isFalse()
        assertThat(custom.contains("시발점 FUCK")).isTrue()
        assertThat(custom.contains("새.금칙어")).isTrue()
        assertThat(ProfanityDictionary(listOf("fuck"), listOf("FUCK"), listOf("FuCk")).contains("fuck")).isFalse()
    }

    @Test
    fun `symbol only terms cannot match every empty compact string and regex syntax stays literal`() {
        val symbols = ProfanityDictionary(listOf("🖕", "..."))
        assertThat(symbols.contains("hello")).isFalse()
        assertThat(symbols.contains("")).isFalse()
        assertThat(symbols.contains("ordinary ... literal")).isTrue()
        assertThat(ProfanityDictionary(listOf("🖕"), exclusions = listOf("🖕")).contains("🖕")).isFalse()
        assertThat(ProfanityDictionary(listOf("a.b")).contains("axb")).isFalse()
        assertThat(ProfanityDictionary(listOf("🖕\uFE0F")).contains("☀\uFE0F")).isFalse()
        assertThat(ProfanityDictionary(listOf("①②")).contains("①.②")).isTrue()
    }

    @Test
    fun `returns every offending field without leaking words`() {
        val error = assertThrows<BusinessException> {
            dictionary.requireClean(linkedMapOf("name" to "씨발", "introduction" to "FUCK", "other" to "기록"))
        }
        assertThat(error.errorCode).isEqualTo(ErrorCode.PROHIBITED_WORD)
        assertThat(error.detail).isEqualTo(mapOf("fields" to listOf("name", "introduction")))
        assertThat(error.message).isEqualTo("error.prohibited_word")
    }

    @Test
    fun `versions are deterministic for ordering duplication and equivalent normalization`() {
        val first = ProfanityDictionary(listOf("씨발", "FUCK", "씨발"))
        val same = ProfanityDictionary(listOf("fuck", Normalizer.normalize("씨발", Normalizer.Form.NFD)))
        assertThat(first.version).isEqualTo(same.version)
        assertThat(first.version).hasSize(64)
        assertThat(ProfanityDictionary(listOf("씨발")).version).isNotEqualTo(first.version)
        assertThat(ProfanityDictionary(emptyList()).contains("normal")).isFalse()
        assertThrows<IllegalArgumentException> { ProfanityDictionary(listOf(" ")) }
    }
}
