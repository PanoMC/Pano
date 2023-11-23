package com.panomc.platform.util.deserializer

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import io.vertx.core.json.JsonObject
import java.lang.reflect.Type

class JsonObjectDeserializer : JsonDeserializer<JsonObject> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): JsonObject {
        return JsonObject(json.asString)
    }
}