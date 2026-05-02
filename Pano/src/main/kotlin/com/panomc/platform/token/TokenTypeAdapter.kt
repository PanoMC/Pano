package com.panomc.platform.token

import com.google.gson.*
import java.lang.reflect.Type

/**
 * Gson type adapter that handles both serialization and deserialization of [TokenType].
 *
 * - **Serialization**: Converts a [TokenType] instance to its [TokenType.getName] string.
 * - **Deserialization**: Resolves a token type name string back to a [TokenType] instance
 *   via the [TokenTypeRegistry].
 */
class TokenTypeAdapter(
    private val tokenTypeRegistry: TokenTypeRegistry
) : JsonSerializer<TokenType>, JsonDeserializer<TokenType> {

    override fun serialize(src: TokenType, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        return JsonPrimitive(src.getName())
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): TokenType {
        val tokenTypeName = json.asString

        return tokenTypeRegistry.get(tokenTypeName)
            ?: throw JsonParseException("Unknown token type: $tokenTypeName")
    }
}
