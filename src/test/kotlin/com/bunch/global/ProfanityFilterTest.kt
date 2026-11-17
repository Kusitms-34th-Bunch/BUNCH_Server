package com.bunch.global

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.profanity.ProfanityFilter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ProfanityFilterTest {
    @TempDir
    lateinit var directory: Path

    private val mapper = jacksonObjectMapper()

    @Test
    fun `default bundled dictionary provides Korean English symbols and provenance`() {
        val filter = ProfanityFilter(mapper, "", "classpath:profanity/dictionary.json")
        listOf("씨발", "ㅅ.ㅂ", "FUCK", "🖕").forEach { assertThat(filter.contains(it)).isTrue() }
        assertThat(filter.contains("행복한 여행 기록")).isFalse()
        listOf("SOURCES.md", "LICENSE.badwords-ko.txt", "LICENSE.LDNOOBW.txt").forEach {
            assertThat(javaClass.getResource("/profanity/$it")).isNotNull()
        }
    }

    @Test
    fun `configured path must load successfully at startup and never silently fall back`() {
        assertThrows<Exception> { filter(directory.resolve("missing.json")) }
        listOf("{", "[]", "{}", "{} {}", "{\"base\":[],\"base\":[],\"additions\":[],\"exclusions\":[]}",
            "{\"base\":[123],\"additions\":[],\"exclusions\":[]}",
            "{\"base\":[\"\"],\"additions\":[],\"exclusions\":[]}").forEach { content ->
            val path = directory.resolve("invalid.json")
            Files.writeString(path, content)
            val error = assertThrows<IllegalStateException> { filter(path) }
            assertThat(error.cause).isNull()
            assertThat(error.message).startsWith("Unable to load profanity dictionary:")
        }
    }

    @Test
    fun `file replacement takes effect only for a new filter instance`() {
        val path = directory.resolve("dictionary.json")
        replace(path, listOf("old"))
        val filter = filter(path)
        val old = filter.current()
        replace(path, listOf("new"))
        assertThat(filter.current()).isSameAs(old)
        assertThat(filter.contains("OLD")).isTrue()
        assertThat(filter.contains("new")).isFalse()
        filter.requireClean(mapOf("title" to "new"))
        assertThat(assertThrows<BusinessException> { filter.requireClean(mapOf("title" to "old")) }.errorCode)
            .isEqualTo(ErrorCode.PROHIBITED_WORD)

        val restarted = filter(path)
        assertThat(restarted.current().version).isNotEqualTo(old.version)
        assertThat(restarted.contains("NEW")).isTrue()
        assertThat(restarted.contains("old")).isFalse()
        restarted.requireClean(mapOf("title" to "old"))
        assertThat(assertThrows<BusinessException> { restarted.requireClean(mapOf("title" to "new")) }.errorCode)
            .isEqualTo(ErrorCode.PROHIBITED_WORD)
        assertThat(old.contains("old")).isTrue()
        assertThat(old.contains("new")).isFalse()
    }

    @Test
    fun `file deletion leaves existing filter usable but prevents a new instance from starting`() {
        val path = directory.resolve("dictionary.json")
        replace(path, listOf("old"))
        val filter = filter(path)
        val snapshot = filter.current()
        Files.delete(path)

        assertThat(filter.current()).isSameAs(snapshot)
        assertThat(filter.contains("OLD")).isTrue()
        assertThat(filter.contains("clean")).isFalse()
        filter.requireClean(mapOf("title" to "clean"))
        assertThat(assertThrows<BusinessException> { filter.requireClean(mapOf("title" to "old")) }.errorCode)
            .isEqualTo(ErrorCode.PROHIBITED_WORD)
        val error = assertThrows<IllegalStateException> { filter(path) }
        assertThat(error.cause).isNull()
        assertThat(error.message).startsWith("Unable to load profanity dictionary:")
    }

    @Test
    fun `file corruption leaves existing filter usable but prevents a new instance from starting`() {
        val path = directory.resolve("dictionary.json")
        replace(path, listOf("old"))
        val filter = filter(path)
        val snapshot = filter.current()
        Files.writeString(path, "{invalid")

        assertThat(filter.current()).isSameAs(snapshot)
        assertThat(filter.contains("OLD")).isTrue()
        assertThat(filter.contains("clean")).isFalse()
        filter.requireClean(mapOf("title" to "clean"))
        assertThat(assertThrows<BusinessException> { filter.requireClean(mapOf("title" to "old")) }.errorCode)
            .isEqualTo(ErrorCode.PROHIBITED_WORD)
        val error = assertThrows<IllegalStateException> { filter(path) }
        assertThat(error.cause).isNull()
        assertThat(error.message).startsWith("Unable to load profanity dictionary:")
    }

    private fun filter(path: Path) = ProfanityFilter(mapper, path.toString(), "classpath:profanity/dictionary.json")

    private fun replace(path: Path, words: List<String>) {
        val temporary = directory.resolve("replacement.json")
        mapper.writeValue(temporary.toFile(), mapOf("base" to words, "additions" to emptyList<String>(),
            "exclusions" to emptyList<String>()))
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
