package com.panomc.platform.util.deserializer

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

class BooleanDeserializer : JsonDeserializer<Boolean> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Boolean {
        return when (json?.asInt) {
            0 -> false
            1 -> true
            else -> throw IllegalStateException("Unexpected value for Boolean: $json")
        }
    }
}