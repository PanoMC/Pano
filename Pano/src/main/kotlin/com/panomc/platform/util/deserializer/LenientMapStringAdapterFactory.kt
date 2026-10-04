package com.panomc.platform.util.deserializer

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.lang.reflect.ParameterizedType

/**
 * Reads a `Map<String, String>` column that is really a JSON object in a text field.
 *
 * The same problem [LenientListStringAdapterFactory] solves, for the same reason: the column is
 * TEXT, so the row arrives as a JSON *string* rather than an object, and a NULL (every row written
 * before the column existed) must become an empty map instead of a null inside a non-null Kotlin
 * type -- entities are allocated through Unsafe, so the field default never runs.
 */
class LenientMapStringAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != Map::class.java) return null

        val arguments = (type.type as? ParameterizedType)?.actualTypeArguments ?: return null

        if (arguments.size != 2) return null
        if (arguments[0] != String::class.java || arguments[1] != String::class.java) return null

        val delegate = gson.getDelegateAdapter(this, type)

        @Suppress("UNCHECKED_CAST")
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) {
                delegate.write(out, value)
            }

            override fun read(reader: JsonReader): T {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()

                    return emptyMap<String, String>() as T
                }

                return try {
                    delegate.read(reader)
                } catch (_: IllegalStateException) {
                    val element = JsonParser.parseString(reader.nextString())

                    if (!element.isJsonObject) {
                        return emptyMap<String, String>() as T
                    }

                    val map = linkedMapOf<String, String>()

                    element.asJsonObject.entrySet().forEach { (key, value) ->
                        if (value.isJsonPrimitive) {
                            map[key] = value.asString
                        }
                    }

                    map as T
                }
            }
        }
    }
}
