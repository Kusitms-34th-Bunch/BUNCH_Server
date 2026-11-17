package com.bunch.global

import com.bunch.global.profanity.ProfanityFilter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `failed reload retains immutable old snapshot and later atomic replacement recovers`() {
        val path = directory.resolve("dictionary.json")
        replace(path, listOf("old"))
        val filter = filter(path)
        val old = filter.current()
        Files.writeString(path, "{invalid")
        assertThat(filter.reload()).isFalse()
        assertThat(filter.current()).isSameAs(old)
        Files.delete(path)
        assertThat(filter.reload()).isFalse()
        assertThat(filter.current()).isSameAs(old)
        replace(path, listOf("new"))
        assertThat(filter.reload()).isTrue()
        assertThat(filter.contains("NEW")).isTrue()
        assertThat(filter.contains("old")).isFalse()
        assertThat(old.contains("old")).isTrue()
        assertThat(old.contains("new")).isFalse()
        assertThat(filter.reload()).isFalse()
    }

    @Test
    fun `concurrent readers always observe one whole dictionary snapshot`() {
        val path = directory.resolve("dictionary.json")
        replace(path, listOf("old"))
        val filter = filter(path)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val reads = (1..2).map {
                pool.submit(Callable {
                    repeat(1000) {
                        val current = filter.current()
                        assertThat(current.contains("old") xor current.contains("new")).isTrue()
                    }
                })
            }
            repeat(12) { index ->
                replace(path, listOf(if (index % 2 == 0) "new" else "old"))
                filter.reload()
            }
            reads.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun filter(path: Path) = ProfanityFilter(mapper, path.toString(), "classpath:profanity/dictionary.json")

    private fun replace(path: Path, words: List<String>) {
        val temporary = directory.resolve("replacement.json")
        mapper.writeValue(temporary.toFile(), mapOf("base" to words, "additions" to emptyList<String>(),
            "exclusions" to emptyList<String>()))
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
