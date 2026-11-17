package com.bunch.global.profanity

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class ProfanityFilter(
    private val mapper: ObjectMapper,
    @Value("\${profanity.dictionary-path:}")
    private val dictionaryPath: String,
    @Value("\${profanity.bundled-resource:classpath:profanity/dictionary.json}")
    bundledResource: String
) {
    private val dictionary: ProfanityDictionary = initialDictionary(bundledResource)

    fun current(): ProfanityDictionary = dictionary

    fun contains(value: String): Boolean = current().contains(value)

    fun requireClean(fields: Map<String, String>) = current().requireClean(fields)

    private fun initialDictionary(bundledResource: String): ProfanityDictionary = try {
        parse(
            if (dictionaryPath.isNotBlank()) readFile()
            else DefaultResourceLoader().getResource(bundledResource).inputStream.use { it.readBytes() }
        )
    } catch (ex: Exception) {
        // Startup failures are also logged by Spring; discard parser causes containing dictionary contents.
        throw IllegalStateException("Unable to load profanity dictionary: ${ex.javaClass.simpleName}")
    }

    private fun readFile(): ByteArray = Files.newInputStream(Path.of(dictionaryPath)).use {
        val bytes = it.readNBytes(MAX_DICTIONARY_BYTES + 1)
        require(bytes.size <= MAX_DICTIONARY_BYTES) { "Dictionary file is too large" }
        bytes
    }

    private fun parse(bytes: ByteArray): ProfanityDictionary {
        val root = mapper.factory.createParser(bytes).use { parser ->
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            val value = mapper.readTree<com.fasterxml.jackson.databind.JsonNode>(parser)
            require(parser.nextToken() == null) { "Dictionary must contain one object" }
            value
        }
        require(root != null && root.isObject) { "Dictionary must be an object" }
        val arrays = listOf("base", "additions", "exclusions").associateWith { name ->
            val array = root.get(name)
            require(array != null && array.isArray) { "Dictionary lists are required" }
            array.map { entry ->
                require(entry.isTextual && entry.textValue().isNotBlank()) { "Dictionary entries must be text" }
                entry.textValue()
            }
        }
        return ProfanityDictionary(arrays.getValue("base"), arrays.getValue("additions"), arrays.getValue("exclusions"))
    }

    private companion object {
        const val MAX_DICTIONARY_BYTES = 4 * 1024 * 1024
    }
}
