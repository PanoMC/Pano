package com.panomc.platform.util.adapter

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.panomc.platform.util.annotation.StrictValidation
import kotlin.reflect.full.primaryConstructor

class StrictNotNullTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val rawType = type.rawType
        if (!rawType.isAnnotationPresent(StrictValidation::class.java)) return null

        val delegate = gson.getDelegateAdapter(this, type)
        val elementAdapter = gson.getAdapter(JsonElement::class.java)

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) {
                delegate.write(out, value)
            }

            override fun read(reader: JsonReader): T {
                val jsonElement = elementAdapter.read(reader)
                val jsonObject = jsonElement.asJsonObject

                // Kotlin reflection ile constructor parametrelerini al
                val kotlinClass = rawType.kotlin
                val primaryConstructor = kotlinClass.primaryConstructor ?: return delegate.fromJsonTree(jsonElement)

                val missingParams = primaryConstructor.parameters
                    .filter { it.type.isMarkedNullable.not() }
                    .mapNotNull { param ->
                        val name = param.name
                        if (name != null && !jsonObject.has(name)) name else null
                    }

                if (missingParams.isNotEmpty()) {
                    throw JsonParseException("Missing non-nullable fields: ${missingParams.joinToString()}")
                }

                return delegate.fromJsonTree(jsonElement)
            }
        }
    }
}
