package com.panomc.platform.schema.dsl

import io.vertx.ext.web.validation.`builder`.ArrayParserFactory
import io.vertx.ext.web.validation.`builder`.ObjectParserFactory
import io.vertx.ext.web.validation.`builder`.ParameterProcessorFactory
import io.vertx.ext.web.validation.`builder`.StyledParameterProcessorFactory
import io.vertx.ext.web.validation.`builder`.TupleParserFactory
import io.vertx.ext.web.validation.impl.ParameterLocation
import com.panomc.platform.schema.SchemaJson
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.ArraySchemaBuilder
import io.vertx.json.schema.common.dsl.BooleanSchemaBuilder
import io.vertx.json.schema.common.dsl.NumberSchemaBuilder
import io.vertx.json.schema.common.dsl.ObjectSchemaBuilder
import io.vertx.json.schema.common.dsl.SchemaBuilder
import io.vertx.json.schema.common.dsl.StringSchemaBuilder
import io.vertx.json.schema.common.dsl.TupleSchemaBuilder
import io.vertx.ext.web.validation.`builder`.Parameters as VertxParameters

/** Same functions as Vert.x's `Parameters`; each returns a [ParamFactory] that remembers name, requiredness and schema. */
object Parameters {
    @JvmStatic fun param(name: String, schema: NumberSchemaBuilder) = plain(name, true, schema, VertxParameters.param(name, schema))
    @JvmStatic fun optionalParam(name: String, schema: NumberSchemaBuilder) = plain(name, false, schema, VertxParameters.optionalParam(name, schema))

    @JvmStatic fun param(name: String, schema: StringSchemaBuilder) = plain(name, true, schema, VertxParameters.param(name, schema))
    @JvmStatic fun optionalParam(name: String, schema: StringSchemaBuilder) = plain(name, false, schema, VertxParameters.optionalParam(name, schema))

    @JvmStatic fun param(name: String, schema: BooleanSchemaBuilder) = plain(name, true, schema, VertxParameters.param(name, schema))
    @JvmStatic fun optionalParam(name: String, schema: BooleanSchemaBuilder) = plain(name, false, schema, VertxParameters.optionalParam(name, schema))

    @JvmStatic fun param(name: String, schema: ArraySchemaBuilder) = plain(name, true, schema, VertxParameters.param(name, schema))
    @JvmStatic fun optionalParam(name: String, schema: ArraySchemaBuilder) = plain(name, false, schema, VertxParameters.optionalParam(name, schema))

    @JvmStatic fun param(name: String, schema: TupleSchemaBuilder) = plain(name, true, schema, VertxParameters.param(name, schema))
    @JvmStatic fun optionalParam(name: String, schema: TupleSchemaBuilder) = plain(name, false, schema, VertxParameters.optionalParam(name, schema))

    @JvmStatic fun param(name: String, schema: ObjectSchemaBuilder) = plain(name, true, schema, VertxParameters.param(name, schema))
    @JvmStatic fun optionalParam(name: String, schema: ObjectSchemaBuilder) = plain(name, false, schema, VertxParameters.optionalParam(name, schema))

    @JvmStatic
    fun param(name: String, schema: SchemaBuilder<*, *>, valueParser: io.vertx.ext.web.validation.impl.parser.ValueParser<String>) =
        plain(name, true, schema, VertxParameters.param(name, schema, valueParser))

    @JvmStatic
    fun optionalParam(name: String, schema: SchemaBuilder<*, *>, valueParser: io.vertx.ext.web.validation.impl.parser.ValueParser<String>) =
        plain(name, false, schema, VertxParameters.optionalParam(name, schema, valueParser))

    @JvmStatic fun jsonParam(name: String, schema: SchemaBuilder<*, *>) = styled(name, true, schema, VertxParameters.jsonParam(name, schema))
    @JvmStatic fun optionalJsonParam(name: String, schema: SchemaBuilder<*, *>) = styled(name, false, schema, VertxParameters.optionalJsonParam(name, schema))

    @JvmStatic fun serializedParam(name: String, parser: ArrayParserFactory, schema: ArraySchemaBuilder) = styled(name, true, schema, VertxParameters.serializedParam(name, parser, schema))
    @JvmStatic fun optionalSerializedParam(name: String, parser: ArrayParserFactory, schema: ArraySchemaBuilder) = styled(name, false, schema, VertxParameters.optionalSerializedParam(name, parser, schema))

    @JvmStatic fun serializedParam(name: String, parser: TupleParserFactory, schema: TupleSchemaBuilder) = styled(name, true, schema, VertxParameters.serializedParam(name, parser, schema))
    @JvmStatic fun optionalSerializedParam(name: String, parser: TupleParserFactory, schema: TupleSchemaBuilder) = styled(name, false, schema, VertxParameters.optionalSerializedParam(name, parser, schema))

    @JvmStatic fun serializedParam(name: String, parser: ObjectParserFactory, schema: ObjectSchemaBuilder) = styled(name, true, schema, VertxParameters.serializedParam(name, parser, schema))
    @JvmStatic fun optionalSerializedParam(name: String, parser: ObjectParserFactory, schema: ObjectSchemaBuilder) = styled(name, false, schema, VertxParameters.optionalSerializedParam(name, parser, schema))

    @JvmStatic fun explodedParam(name: String, schema: ArraySchemaBuilder) = styled(name, true, schema, VertxParameters.explodedParam(name, schema))
    @JvmStatic fun optionalExplodedParam(name: String, schema: ArraySchemaBuilder) = styled(name, false, schema, VertxParameters.optionalExplodedParam(name, schema))

    @JvmStatic fun explodedParam(name: String, schema: TupleSchemaBuilder) = styled(name, true, schema, VertxParameters.explodedParam(name, schema))
    @JvmStatic fun optionalExplodedParam(name: String, schema: TupleSchemaBuilder) = styled(name, false, schema, VertxParameters.optionalExplodedParam(name, schema))

    @JvmStatic fun explodedParam(name: String, schema: ObjectSchemaBuilder) = styled(name, true, schema, VertxParameters.explodedParam(name, schema))
    @JvmStatic fun optionalExplodedParam(name: String, schema: ObjectSchemaBuilder) = styled(name, false, schema, VertxParameters.optionalExplodedParam(name, schema))

    @JvmStatic fun deepObjectParam(name: String, schema: ObjectSchemaBuilder) = styled(name, true, schema, VertxParameters.deepObjectParam(name, schema))
    @JvmStatic fun optionalDeepObjectParam(name: String, schema: ObjectSchemaBuilder) = styled(name, false, schema, VertxParameters.optionalDeepObjectParam(name, schema))

    private fun plain(name: String, required: Boolean, schema: SchemaBuilder<*, *>, factory: ParameterProcessorFactory) =
        ParamFactory(name, required, SchemaJson.of(schema)) { location: ParameterLocation, repo: SchemaRepository ->
            factory.create(location, repo)
        }

    private fun styled(name: String, required: Boolean, schema: SchemaBuilder<*, *>, factory: StyledParameterProcessorFactory) =
        ParamFactory(name, required, SchemaJson.of(schema)) { location: ParameterLocation, repo: SchemaRepository ->
            factory.create(location, repo)
        }
}
