package com.panomc.platform.schema

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.json.schema.common.dsl.SchemaBuilder

/**
 * `SchemaBuilder.toJson()` as a stable JSON Schema (doc 04 section 5, slice B1).
 *
 * The builder is usable as it is, with one catch: every builder, nested ones included, stamps a random
 * `"$id": "urn:vertxschemas:<uuid>"` on its output, which changes on every boot and would make a committed
 * OpenAPI snapshot differ each time. Those ids are dropped; an `$id` an author set on purpose (`pano:Post`) stays.
 */
object SchemaJson {
    private const val GENERATED_ID_PREFIX = "urn:vertxschemas:"

    fun of(builder: SchemaBuilder<*, *>): JsonObject = clean(builder.toJson())

    /** A copy of [schema] without the generated `$id`s, at every depth. */
    fun clean(schema: JsonObject): JsonObject {
        val copy = JsonObject()

        schema.forEach { (key, value) ->
            if (key == "\$id" && value is String && value.startsWith(GENERATED_ID_PREFIX)) {
                return@forEach
            }

            copy.put(key, cleanValue(value))
        }

        return copy
    }

    private fun cleanValue(value: Any?): Any? = when (value) {
        is JsonObject -> clean(value)
        is Map<*, *> -> clean(JsonObject(value.entries.associate { it.key.toString() to it.value }))
        is JsonArray -> JsonArray(value.list.map { cleanValue(it) })
        is List<*> -> JsonArray(value.map { cleanValue(it) })
        else -> value
    }
}
