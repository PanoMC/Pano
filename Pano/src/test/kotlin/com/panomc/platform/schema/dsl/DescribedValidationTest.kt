package com.panomc.platform.schema.dsl

import com.panomc.platform.model.Api
import com.panomc.platform.schema.SchemaJson
import com.panomc.platform.route.api.panel.PanelGetActivityLogsAPI
import com.panomc.platform.route.api.panel.locale.PanelGetLocaleTranslationsAPI
import com.panomc.platform.route.api.panel.post.category.PanelUpdatePostCategoryAPI
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.Draft
import io.vertx.json.schema.JsonSchema
import io.vertx.json.schema.JsonSchemaOptions
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.Validator
import io.vertx.json.schema.common.dsl.Keywords
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.objenesis.ObjenesisStd
import java.util.concurrent.TimeUnit

/**
 * Doc 04 section 5 / slice B1: what the schema DSL records, and the proof that `SchemaBuilder.toJson()` is
 * usable JSON Schema (so the wrapper stores the JSON and does not need to keep the builder).
 */
class DescribedValidationTest {
    private val repository: SchemaRepository =
        SchemaRepository.create(JsonSchemaOptions().setBaseUri("https://panomc.com").setDraft(Draft.DRAFT7))

    // toJson() feasibility

    private fun validator(schema: JsonObject) =
        Validator.create(JsonSchema.of(schema), JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("app://pano"))

    @Test
    fun `toJson of an object builder is a JSON Schema a validator accepts and rejects with`() {
        val json = SchemaJson.of(
            objectSchema()
                .requiredProperty("name", stringSchema().with(Keywords.minLength(2)))
                .optionalProperty("age", intSchema())
                .optionalProperty("tags", arraySchema().items(stringSchema()))
        )

        assertEquals("object", json.getString("type"))
        assertEquals(JsonArray().add("name"), json.getJsonArray("required"))
        assertEquals("string", json.getJsonObject("properties").getJsonObject("name").getString("type"))
        assertEquals(2, json.getJsonObject("properties").getJsonObject("name").getInteger("minLength"))
        assertEquals("integer", json.getJsonObject("properties").getJsonObject("age").getString("type"))
        assertEquals("array", json.getJsonObject("properties").getJsonObject("tags").getString("type"))

        val validator = validator(json)

        assertTrue(validator.validate(JsonObject().put("name", "ab").put("age", 3).put("tags", JsonArray().add("x"))).valid)
        assertFalse(validator.validate(JsonObject().put("name", "a")).valid)
        assertFalse(validator.validate(JsonObject()).valid)
        assertFalse(validator.validate(JsonObject().put("name", "ab").put("age", "three")).valid)
    }

    @Test
    fun `toJson stamps a random id on every builder and SchemaJson drops it but keeps an id set on purpose`() {
        val raw = objectSchema().requiredProperty("a", stringSchema()).toJson()

        assertTrue(raw.getString("\$id").startsWith("urn:vertxschemas:"))
        assertTrue(raw.getJsonObject("properties").getJsonObject("a").getString("\$id").startsWith("urn:vertxschemas:"))

        val cleaned = SchemaJson.of(objectSchema().requiredProperty("a", stringSchema()))

        assertFalse(cleaned.containsKey("\$id"))
        assertEquals(JsonObject().put("type", "string"), cleaned.getJsonObject("properties").getJsonObject("a"))
        // two builds of the same shape are equal, which a committed snapshot needs
        assertEquals(cleaned, SchemaJson.of(objectSchema().requiredProperty("a", stringSchema())))

        val named = SchemaJson.of(objectSchema().id(io.vertx.core.json.pointer.JsonPointer.fromURI(java.net.URI("pano:Post"))).requiredProperty("a", stringSchema()))

        // the builder writes an explicit id as a URI with an empty fragment
        assertEquals("pano:Post#", named.getString("\$id"))
    }

    @Test
    fun `toJson of the scalar builders carries the type and an enum its values`() {
        assertEquals(JsonObject().put("type", "string"), SchemaJson.of(stringSchema()))
        assertEquals("number", SchemaJson.of(numberSchema()).getString("type"))
        assertEquals("boolean", SchemaJson.of(booleanSchema()).getString("type"))

        val enumJson = SchemaJson.of(enumSchema("A", "B"))

        assertEquals(JsonArray().add("A").add("B"), enumJson.getJsonArray("enum"))
        assertTrue(validator(enumJson).validate("A").valid)
        assertFalse(validator(enumJson).validate("C").valid)
    }

    // recorded parameters equal a hand-written list for three endpoints

    private val objenesis = ObjenesisStd()

    /** The endpoint class without its constructor: building the validation handler touches no field. */
    private fun validationOf(type: Class<out Api>): DescribedValidation {
        val handler = objenesis.newInstance(type).getValidationHandler(repository)

        assertTrue(handler is DescribedValidation, "${type.simpleName} must build its handler with the schema DSL")

        return handler as DescribedValidation
    }

    @Test
    fun `PanelGetActivityLogsAPI records its optional query parameters`() {
        val validation = validationOf(PanelGetActivityLogsAPI::class.java)

        assertEquals(
            listOf(
                ParamSpec(ParamLocation.QUERY, "page", false, SchemaJson.of(intSchema())),
                ParamSpec(ParamLocation.QUERY, "pageSize", false, SchemaJson.of(intSchema())),
                ParamSpec(ParamLocation.QUERY, "search", false, SchemaJson.of(stringSchema())),
                ParamSpec(ParamLocation.QUERY, "locale", false, SchemaJson.of(stringSchema()))
            ),
            validation.parameters
        )
        assertNull(validation.body)
        assertTrue(validation.bodies.isEmpty())
    }

    @Test
    fun `PanelGetLocaleTranslationsAPI records two path parameters and an optional array query`() {
        val validation = validationOf(PanelGetLocaleTranslationsAPI::class.java)

        val filter = validation.parameters.single { it.name == "filter" }

        assertEquals(
            listOf(
                ParamSpec(ParamLocation.PATH, "localeId", true, SchemaJson.of(numberSchema())),
                ParamSpec(ParamLocation.PATH, "type", true, SchemaJson.of(stringSchema())),
                ParamSpec(ParamLocation.QUERY, "filter", false, filter.schemaJson)
            ),
            validation.parameters
        )
        assertEquals("array", filter.schemaJson.getString("type"))
        assertTrue(filter.schemaJson.getJsonObject("items").getJsonArray("enum").size() > 0)
    }

    @Test
    fun `PanelUpdatePostCategoryAPI records a path parameter and a JSON body`() {
        val validation = validationOf(PanelUpdatePostCategoryAPI::class.java)

        val expectedBody = SchemaJson.of(
            objectSchema()
                .requiredProperty("title", stringSchema())
                .requiredProperty("description", stringSchema())
                .requiredProperty("url", stringSchema())
                .requiredProperty("color", stringSchema())
        )

        assertEquals(listOf(ParamSpec(ParamLocation.PATH, "id", true, SchemaJson.of(numberSchema()))), validation.parameters)
        assertEquals(listOf(BodySpec(Bodies.JSON, expectedBody)), validation.bodies)
        assertEquals(expectedBody, validation.body)
    }

    // the builder keeps its declared order and every body kind

    @Test
    fun `the builder records every location and every body kind in declaration order`() {
        val validation = ValidationHandlerBuilder.create(repository)
            .pathParameter(Parameters.param("id", intSchema()))
            .queryParameter(Parameters.optionalParam("flag", booleanSchema()))
            .headerParameter(Parameters.param("X-Thing", stringSchema()))
            .cookieParameter(Parameters.optionalParam("seen", stringSchema()))
            .queryParameter(Parameters.explodedParam("ids", arraySchema().items(intSchema())))
            .body(Bodies.multipartFormData(objectSchema().optionalProperty("note", stringSchema())))
            .body(Bodies.json(objectSchema().requiredProperty("a", stringSchema())))
            .build() as DescribedValidation

        assertEquals(
            listOf(
                ParamSpec(ParamLocation.PATH, "id", true, SchemaJson.of(intSchema())),
                ParamSpec(ParamLocation.QUERY, "flag", false, SchemaJson.of(booleanSchema())),
                ParamSpec(ParamLocation.HEADER, "X-Thing", true, SchemaJson.of(stringSchema())),
                ParamSpec(ParamLocation.COOKIE, "seen", false, SchemaJson.of(stringSchema())),
                ParamSpec(ParamLocation.QUERY, "ids", true, SchemaJson.of(arraySchema().items(intSchema())))
            ),
            validation.parameters
        )
        assertEquals(listOf(Bodies.MULTIPART_FORM_DATA, Bodies.JSON), validation.bodies.map { it.contentType })
        // the JSON body wins as "the" body even though it came second
        assertEquals(SchemaJson.of(objectSchema().requiredProperty("a", stringSchema())), validation.body)
        assertEquals("query", validation.parameters[1].location.openApi)
    }

    @Test
    fun `an empty builder describes nothing`() {
        val validation = ValidationHandlerBuilder.create(repository).build() as DescribedValidation

        assertTrue(validation.parameters.isEmpty())
        assertNull(validation.body)
    }

    // the wrapper still validates exactly as Vert.x does

    @Test
    fun `the built handler still validates requests`() {
        val vertx = Vertx.vertx()

        try {
            val router = Router.router(vertx)
            val handler: ValidationHandler = ValidationHandlerBuilder.create(repository)
                .queryParameter(Parameters.param("page", intSchema()))
                .build()

            router.get("/x")
                .handler(handler)
                .handler { it.response().end("ok") }
                .failureHandler { it.response().setStatusCode(400).end("bad") }

            val port = vertx.createHttpServer().requestHandler(router).listen(0).toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS).actualPort()

            val client = vertx.createHttpClient()

            fun status(path: String): Int = client.request(HttpMethod.GET, port, "localhost", path)
                .compose { it.send() }
                .map { it.statusCode() }
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)

            assertEquals(200, status("/x?page=2"))
            assertEquals(400, status("/x?page=two"))
            assertEquals(400, status("/x"))
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }
    }
}
