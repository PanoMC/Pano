package com.panomc.platform.util.deserializer

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.lang.reflect.ParameterizedType

class LenientListLongAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (!List::class.java.isAssignableFrom(type.rawType)) return null

        val elementType = (type.type as? ParameterizedType)?.actualTypeArguments?.get(0)
        if (elementType != Long::class.javaObjectType && elementType != java.lang.Long::class.java) return null

        val delegate = gson.getDelegateAdapter(this, type)

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) {
                delegate.write(out, value)
            }

            @Suppress("UNCHECKED_CAST")
            override fun read(reader: JsonReader): T? {
                val peek = reader.peek()

                if (peek == JsonToken.STRING) {
                    val value = reader.nextString()
                    if (value.isNullOrEmpty()) {
                        return mutableListOf<Long>() as T
                    }

                    // If the database returned a JSON string like "[1,2,3]"
                    if (value.startsWith("[") && value.endsWith("]")) {
                        try {
                            val array = JsonParser.parseString(value).asJsonArray
                            val list = mutableListOf<Long>()
                            array.forEach {
                                if (it.isJsonPrimitive) list.add(it.asLong)
                            }
                            return list as T
                        } catch (e: Exception) {
                            return mutableListOf<Long>() as T
                        }
                    }

                    // Fallback for single value strings "123"
                    return try {
                        mutableListOf(value.toLong()) as T
                    } catch (e: Exception) {
                        mutableListOf<Long>() as T
                    }
                }

                if (peek == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }

                return try {
                    delegate.read(reader)
                } catch (e: Exception) {
                    // Final safety net: if delegate fails despite not being a string (e.g. malformed array)
                    if (reader.peek() != JsonToken.END_DOCUMENT) {
                        try { reader.skipValue() } catch (_: Exception) {}
                    }
                    mutableListOf<Long>() as T
                }
            }
        }.nullSafe()
    }
}