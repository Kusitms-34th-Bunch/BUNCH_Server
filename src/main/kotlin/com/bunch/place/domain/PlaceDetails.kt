package com.bunch.place.domain

import java.util.Locale
import java.util.regex.Pattern

/** Shared, provider-independent place input. This does not verify a provider search result. */
data class PlaceDetails private constructor(
    val provider: String,
    val externalId: String,
    val name: String,
    val address: String
) {
    companion object {
        private val providerPattern = Regex("[a-z0-9_-]{1,32}")
        private val graphemePattern = Pattern.compile("\\X")

        fun of(provider: String, externalId: String, name: String, address: String): PlaceDetails {
            val source = provider.trim().lowercase(Locale.ROOT)
            val id = externalId.trim()
            val title = name.trim()
            val street = address.trim()
            require(providerPattern.matches(source))
            require(id.isNotBlank() && id.codePointCount(0, id.length) <= 128)
            require(validText(title, 200))
            require(validText(street, 500))
            require(listOf(source, id, title, street).none { '\u0000' in it })
            return PlaceDetails(source, id, title, street)
        }

        private fun validText(value: String, maximum: Int): Boolean =
            value.isNotBlank() &&
                graphemePattern.matcher(value).results().limit(maximum.toLong() + 1).count() <= maximum
    }
}
