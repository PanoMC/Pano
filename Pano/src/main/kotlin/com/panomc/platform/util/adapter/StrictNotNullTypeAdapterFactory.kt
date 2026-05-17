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
                    // Parameters with a default value in Kotlin (`val foo: Int = 0`) are
                    // already safe to omit from JSON — Kotlin fills them in. The strict
                    // check only needs to reject fields that have no default AND aren't
                    // nullable. Without this guard, adding a new backwards-compatible
                    // field with a default value would break every old manifest.json that
                    // didn't ship with it (e.g. embedded setup-ui / panel-ui manifests
                    // pre-dating ThemeManifest.premium).
                    .filter { !it.isOptional }
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
