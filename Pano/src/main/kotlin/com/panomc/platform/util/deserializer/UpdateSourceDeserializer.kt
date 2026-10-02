package com.panomc.platform.util.deserializer

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.panomc.platform.util.UpdateSource
import java.lang.reflect.Type

/** A missing, blank or unknown `update-source` falls back to [UpdateSource.AUTO] instead of failing the boot. */
class UpdateSourceDeserializer : JsonDeserializer<UpdateSource> {
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): UpdateSource {
        val value = try {
            json.asString
        } catch (_: Exception) {
            null
        }

        return UpdateSource.parse(value) ?: UpdateSource.AUTO
    }
}
