package com.panomc.platform.schema

import com.panomc.platform.model.Error
import io.vertx.core.json.JsonObject
import io.vertx.json.schema.Draft
import io.vertx.json.schema.JsonSchema
import io.vertx.json.schema.JsonSchemaOptions
import io.vertx.json.schema.OutputFormat
import io.vertx.json.schema.Validator
import io.vertx.json.schema.common.dsl.SchemaBuilder
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.reflect.KClass

/** Who may rely on an operation (doc 04 section 6). `INTERNAL` carries no promise. */
enum class Stability { PUBLIC, INTERNAL }

/**
 * An endpoint's hand-written description for the OpenAPI document (doc 04 section 5). Opt-in: an endpoint
 * without one is listed as undocumented.
 *
 * [response] is the success body, an object schema that never declares `error` or `result`. A list
 * endpoint gives [paginatedItem] instead, the schema of one item; its body is `{ items: [item], page: {...} }`.
 * [tag] defaults to the first segment of the path. [binary] marks a file or stream answer, which is not checked.
 */
class EndpointDoc(
    val summary: String,
    val tag: String? = null,
    val response: SchemaBuilder<*, *>? = null,
    val paginatedItem: SchemaBuilder<*, *>? = null,
    val errors: List<KClass<out Error>> = listOf(),
    val binary: Boolean = false
) {
    /** The JSON Schema of the whole success body, or `null` when nothing is declared or the answer is binary. */
    val responseSchema: JsonObject? by lazy {
        when {
            binary -> null
            response != null -> SchemaJson.of(response)
            paginatedItem != null -> JsonObject()
                .put("type", "object")
                .put(
                    "properties", JsonObject()
                        .put("items", JsonObject().put("type", "array").put("items", SchemaJson.of(paginatedItem)))
                        .put("page", JsonObject().put("type", "object"))
                )
                .put("required", io.vertx.core.json.JsonArray().add("items").add("page"))

            else -> null
        }
    }

    private val validator: Validator? by lazy {
        responseSchema?.let {
            Validator.create(
                JsonSchema.of(it),
                JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("app://pano").setOutputFormat(OutputFormat.Basic)
            )
        }
    }

    /**
     * Why this description cannot be used, or `null` when it can: the response (or the item of a list) must be an
     * object schema, must not declare `error` or `result`, and [response] and [paginatedItem] are exclusive.
     */
    fun problem(): String? {
        if (response != null && paginatedItem != null) {
            return "declares both response and paginatedItem; use one"
        }

        listOf("response" to response, "paginatedItem" to paginatedItem).forEach { (label, builder) ->
            if (builder == null) {
                return@forEach
            }

            val schema = SchemaJson.of(builder)

            if (schema.getString("type") != "object") {
                return "$label must be an object schema, not ${schema.getValue("type") ?: "an untyped schema"}"
            }

            val reserved = RESERVED_KEYS.filter { schema.getJsonObject("properties")?.containsKey(it) == true }

            if (reserved.isNotEmpty()) {
                return "$label must not declare ${reserved.joinToString(" or ") { "\"$it\"" }}; the error envelope owns `error` and success bodies have no `result`"
            }
        }

        return null
    }

    /**
     * Checks [body] against the declared schema and returns one line per failure, each naming the JSON pointer of
     * the offending value. An empty list means it matches, or nothing is declared. Never throws.
     */
    fun check(body: JsonObject): List<String> {
        val validator = runCatching { validator }.getOrNull() ?: return emptyList()

        return try {
            val unit = validator.validate(body)

            if (unit.valid == true) {
                emptyList()
            } else {
                val leaves = unit.errors.orEmpty().ifEmpty { listOf(unit) }

                leaves.map { "${pointerOf(it.instanceLocation)}: ${it.error}" }.distinct()
            }
        } catch (e: Exception) {
            listOf("/: the declared schema cannot be evaluated (${e.message})")
        }
    }

    /** `#/items/0/id` as the JSON pointer `/items/0/id`; the document itself is `/`. */
    private fun pointerOf(instanceLocation: String?): String =
        instanceLocation?.removePrefix("#")?.ifEmpty { null } ?: "/"

    companion object {
        private val RESERVED_KEYS = listOf("error", "result")
    }
}

/**
 * Marks an endpoint as going away: OpenAPI `deprecated: true`, the `Deprecation: true` and `Sunset` response
 * headers and one warning per endpoint per boot. [sinceLevel] is the API level that deprecated it, [removeAfter] an
 * ISO date (`2027-06-01`), [replacement] a hint such as `GET /posts`.
 */
class Deprecation(val sinceLevel: Int, val removeAfter: String, val replacement: String? = null) {
    private val removeDate: LocalDate = try {
        LocalDate.parse(removeAfter)
    } catch (e: Exception) {
        throw IllegalArgumentException("removeAfter must be an ISO date like 2027-06-01, got \"$removeAfter\"", e)
    }

    /** [removeAfter] as the HTTP date of the `Sunset` header. */
    val sunset: String = HTTP_DATE.format(removeDate.atStartOfDay(ZoneOffset.UTC))

    private companion object {
        // RFC 9110 IMF-fixdate: a two digit day, which Java's RFC_1123_DATE_TIME does not print.
        val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
    }
}
