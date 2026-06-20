package com.panomc.platform.util

import io.vertx.core.json.JsonObject

object JsonObjectUtil {
    fun flattenJsonObject(
        jsonObject: JsonObject,
        parentKey: String = "",
        sep: String = "."
    ): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for ((key, value) in jsonObject) {
            val newKey = if (parentKey.isEmpty()) key else "$parentKey$sep$key"
            when (value) {
                is JsonObject -> {
                    result.putAll(flattenJsonObject(value, newKey, sep))
                }

                is io.vertx.core.json.JsonArray -> {
                    value.forEachIndexed { index, element ->
                        val arrayKey = "$newKey[$index]"
                        when (element) {
                            is JsonObject -> {
                                result.putAll(flattenJsonObject(element, arrayKey, sep))
                            }

                            else -> {
                                result[arrayKey] = element ?: ""
                            }
                        }
                    }
                }

                else -> {
                    result[newKey] = value ?: ""
                }
            }
        }
        return result
    }

    fun unflattenToJsonObject(flatMap: Map<String, Any>, sep: String = "."): JsonObject {
        val root = JsonObject()

        for ((flatKey, value) in flatMap) {
            val keys = flatKey.split(sep)
            var current = root
            for (i in 0 until keys.size - 1) {
                val key = keys[i]
                // An intermediate segment may already hold a non-object (prefix-colliding keys
                // like "a.b" and "a.b.c"). Don't blindly cast via getJsonObject (which throws a
                // ClassCastException); overwrite with a fresh object instead (last-write-wins).
                val existing = current.getValue(key)
                val next = if (existing is JsonObject) {
                    existing
                } else {
                    JsonObject().also { current.put(key, it) }
                }
                current = next
            }
            current.put(keys.last(), value)
        }
        return root
    }
}