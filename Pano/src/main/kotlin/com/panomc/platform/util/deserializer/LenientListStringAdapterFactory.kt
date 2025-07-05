package com.panomc.platform.util.deserializer

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.lang.reflect.ParameterizedType

class LenientListStringAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != List::class.java) return null

        val elementType = (type.type as? ParameterizedType)?.actualTypeArguments?.get(0)
        if (elementType != String::class.java) return null

        val delegate = gson.getDelegateAdapter(this, type)

        @Suppress("UNCHECKED_CAST")
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) {
                delegate.write(out, value)
            }

            override fun read(reader: JsonReader): T {
                return try {
                    delegate.read(reader)
                } catch (e: IllegalStateException) {
                    val jsonString = reader.nextString()
                    val jsonElement = JsonParser.parseString(jsonString)

                    if (jsonElement.isJsonArray) {
                        val list = mutableListOf<String>()
                        jsonElement.asJsonArray.forEach { el ->
                            list.add(el.asString)
                        }
                        list as T
                    } else {
                        emptyList<String>() as T
                    }
                }
            }
        }
    }
}
