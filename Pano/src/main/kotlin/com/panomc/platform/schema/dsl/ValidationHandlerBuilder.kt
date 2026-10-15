package com.panomc.platform.schema.dsl

import io.vertx.core.json.JsonObject
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.`builder`.BodyProcessorFactory
import io.vertx.ext.web.validation.`builder`.ParameterProcessorFactory
import io.vertx.ext.web.validation.impl.ParameterLocation
import io.vertx.ext.web.validation.impl.body.BodyProcessor
import io.vertx.ext.web.validation.impl.parameter.ParameterProcessor
import io.vertx.json.schema.SchemaRepository

/**
 * A request parameter as [Parameters] builds it: Vert.x's factory plus what is needed to describe it.
 * It is a Vert.x [ParameterProcessorFactory], so it works wherever one is expected.
 */
class ParamFactory internal constructor(
    val name: String,
    val required: Boolean,
    val schemaJson: JsonObject,
    private val delegate: (ParameterLocation, SchemaRepository) -> ParameterProcessor
) : ParameterProcessorFactory {
    override fun create(location: ParameterLocation, schemaRepo: SchemaRepository): ParameterProcessor =
        delegate(location, schemaRepo)
}

/** A request body as [Bodies] builds it: Vert.x's factory plus its media type and schema. */
class BodyFactory internal constructor(
    val contentType: String,
    val schemaJson: JsonObject,
    private val delegate: BodyProcessorFactory
) : BodyProcessorFactory {
    override fun create(schemaRepository: SchemaRepository): BodyProcessor = delegate.create(schemaRepository)
}

/**
 * The same builder as Vert.x's `ValidationHandlerBuilder` (same method names and arguments), which it
 * delegates to, and the place the endpoint's request description is recorded. The handler it builds
 * is a [DescribedValidation].
 */
class ValidationHandlerBuilder private constructor(schemaRepository: SchemaRepository) {
    private val delegate = io.vertx.ext.web.validation.`builder`.ValidationHandlerBuilder.create(schemaRepository)
    private val parameters = mutableListOf<ParamSpec>()
    private val bodies = mutableListOf<BodySpec>()

    fun queryParameter(factory: ParamFactory): ValidationHandlerBuilder {
        delegate.queryParameter(factory as ParameterProcessorFactory)

        return record(ParamLocation.QUERY, factory)
    }

    fun pathParameter(factory: ParamFactory): ValidationHandlerBuilder {
        delegate.pathParameter(factory)

        return record(ParamLocation.PATH, factory)
    }

    fun cookieParameter(factory: ParamFactory): ValidationHandlerBuilder {
        delegate.cookieParameter(factory as ParameterProcessorFactory)

        return record(ParamLocation.COOKIE, factory)
    }

    fun headerParameter(factory: ParamFactory): ValidationHandlerBuilder {
        delegate.headerParameter(factory)

        return record(ParamLocation.HEADER, factory)
    }

    fun body(factory: BodyFactory): ValidationHandlerBuilder {
        delegate.body(factory as BodyProcessorFactory)
        bodies.add(BodySpec(factory.contentType, factory.schemaJson))

        return this
    }

    /** A hand-made Vert.x processor; it is applied but cannot be described. */
    fun body(processor: BodyProcessor): ValidationHandlerBuilder {
        delegate.body(processor)

        return this
    }

    fun predicate(predicate: RequestPredicate): ValidationHandlerBuilder {
        delegate.predicate(predicate)

        return this
    }

    fun build(): ValidationHandler =
        DescribedValidationHandler(delegate.build(), parameters.toList(), bodies.toList())

    private fun record(location: ParamLocation, factory: ParamFactory): ValidationHandlerBuilder {
        parameters.add(ParamSpec(location, factory.name, factory.required, factory.schemaJson))

        return this
    }

    companion object {
        @JvmStatic
        fun create(schemaRepository: SchemaRepository) = ValidationHandlerBuilder(schemaRepository)
    }
}

/** The Vert.x validation handler, which it runs unchanged, with the description recorded for it. */
class DescribedValidationHandler internal constructor(
    private val delegate: ValidationHandler,
    override val parameters: List<ParamSpec>,
    override val bodies: List<BodySpec>
) : ValidationHandler by delegate, DescribedValidation
