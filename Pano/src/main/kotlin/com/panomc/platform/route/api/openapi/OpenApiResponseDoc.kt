package com.panomc.platform.route.api.openapi

import com.panomc.platform.model.Error
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import kotlin.reflect.KClass

/** What the three `openapi.json` endpoints answer: an OpenAPI 3.1 document (doc 04 section 5). */
internal object OpenApiResponseDoc {
    private val document = objectSchema()
        .requiredProperty("openapi", stringSchema())
        .requiredProperty("info", objectSchema())
        .requiredProperty("servers", arraySchema())
        .requiredProperty("paths", objectSchema())
        .requiredProperty("components", objectSchema())

    fun of(summary: String, errors: List<KClass<out Error>> = listOf()) =
        EndpointDoc(summary = summary, tag = "openapi", response = document, errors = errors)
}
