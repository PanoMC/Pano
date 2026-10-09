package com.panomc.platform.schema.dsl

// The files of this package name Vert.x's own builder package with the word `builder` in backticks. That is not
// decoration: `migrate-v1 --only c` swaps the plain prefix to this package everywhere, and would turn these
// delegations into calls to themselves.

import io.vertx.core.json.JsonObject

/** Where a request parameter is read from. [openApi] is the OpenAPI `in` value. */
enum class ParamLocation(val openApi: String) {
    QUERY("query"),
    PATH("path"),
    HEADER("header"),
    COOKIE("cookie")
}

/**
 * One request parameter an endpoint declares, recorded while its validation handler is built
 * (doc 04 section 5). [schemaJson] is the JSON Schema of the value, as `SchemaBuilder.toJson()` gives it.
 */
data class ParamSpec(
    val location: ParamLocation,
    val name: String,
    val required: Boolean,
    val schemaJson: JsonObject
)

/** One request body an endpoint accepts: its media type and the JSON Schema of the body. */
data class BodySpec(val contentType: String, val schemaJson: JsonObject)

/**
 * What a validation handler built by [ValidationHandlerBuilder] knows about the request it checks.
 * The OpenAPI generator reads it from the route table; a handler written against Vert.x directly
 * (a plugin not yet moved) does not implement it, and its endpoint is documented without request details.
 */
interface DescribedValidation {
    /** Every query, path, header and cookie parameter, in the order they were declared. */
    val parameters: List<ParamSpec>

    /** Every body, in the order they were declared. */
    val bodies: List<BodySpec>

    /** The schema of the first JSON body, else of the first body; `null` without a body. */
    val body: JsonObject?
        get() = (bodies.firstOrNull { it.contentType == Bodies.JSON } ?: bodies.firstOrNull())?.schemaJson
}
