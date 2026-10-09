package com.panomc.platform.schema.dsl

import com.panomc.platform.schema.SchemaJson
import io.vertx.json.schema.common.dsl.ObjectSchemaBuilder
import io.vertx.json.schema.common.dsl.SchemaBuilder
import io.vertx.json.schema.common.dsl.StringSchemaBuilder
import io.vertx.ext.web.validation.`builder`.Bodies as VertxBodies

/** Same functions as Vert.x's `Bodies`; each returns a [BodyFactory] that remembers its schema. */
object Bodies {
    const val JSON = "application/json"
    const val TEXT_PLAIN = "text/plain"
    const val FORM_URL_ENCODED = "application/x-www-form-urlencoded"
    const val MULTIPART_FORM_DATA = "multipart/form-data"

    @JvmStatic
    fun json(schemaBuilder: SchemaBuilder<*, *>) = BodyFactory(JSON, SchemaJson.of(schemaBuilder), VertxBodies.json(schemaBuilder))

    @JvmStatic
    fun textPlain(schemaBuilder: StringSchemaBuilder) =
        BodyFactory(TEXT_PLAIN, SchemaJson.of(schemaBuilder), VertxBodies.textPlain(schemaBuilder))

    @JvmStatic
    fun formUrlEncoded(schemaBuilder: ObjectSchemaBuilder) =
        BodyFactory(FORM_URL_ENCODED, SchemaJson.of(schemaBuilder), VertxBodies.formUrlEncoded(schemaBuilder))

    @JvmStatic
    fun multipartFormData(schemaBuilder: ObjectSchemaBuilder) =
        BodyFactory(MULTIPART_FORM_DATA, SchemaJson.of(schemaBuilder), VertxBodies.multipartFormData(schemaBuilder))
}
