package com.bunch.notification.dto

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException

class PushBooleanDeserializer : JsonDeserializer<Boolean>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Boolean =
        when (parser.currentToken) {
            JsonToken.VALUE_TRUE -> true
            JsonToken.VALUE_FALSE -> false
            else -> throw JsonMappingException.from(parser, "Expected JSON boolean")
        }

    override fun getNullValue(context: DeserializationContext): Boolean =
        throw JsonMappingException.from(context.parser, "Expected JSON boolean")
}
