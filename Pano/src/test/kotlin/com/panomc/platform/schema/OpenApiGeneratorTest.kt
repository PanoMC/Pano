package com.panomc.platform.schema

import com.panomc.platform.db.model.Notification
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.Api
import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.PanelApi
import com.panomc.platform.notification.NotificationType
import com.panomc.platform.route.ApiPaths
import com.panomc.platform.route.Mount
import com.panomc.platform.route.Namespace
import com.panomc.platform.route.RouteEntry
import com.panomc.platform.route.RouteTable
import com.panomc.platform.route.api.GetSitemapAPI
import com.panomc.platform.route.api.notification.GetNotificationsAPI
import com.panomc.platform.route.api.openapi.GetOpenApiAPI
import com.panomc.platform.route.api.openapi.PanelGetOpenApiAPI
import com.panomc.platform.route.api.plugins.GetPluginOpenApiAPI
import com.panomc.platform.route.api.posts.GetPostDetailAPI
import com.panomc.platform.route.api.posts.GetPostsAPI as CoreGetPostsAPI
import com.panomc.platform.route.api.posts.GetPostsService
import com.panomc.platform.route.api.server.GetServersAPI
import com.panomc.platform.route.api.ticket.GetTicketsService
import com.panomc.platform.schema.dsl.BodySpec
import com.panomc.platform.schema.dsl.DescribedValidation
import com.panomc.platform.schema.dsl.ParamLocation
import com.panomc.platform.schema.dsl.ParamSpec
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.json.schema.Draft
import io.vertx.json.schema.JsonSchema
import io.vertx.json.schema.JsonSchemaOptions
import io.vertx.json.schema.OutputFormat
import io.vertx.json.schema.OutputUnit
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.Validator
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.objenesis.ObjenesisStd
import org.pf4j.PluginState

// Endpoint classes for hand-built RouteEntry lists. Only the class matters (its name, its base class); none is ever
// instantiated here, so they stay abstract.
private abstract class GetPostsAPI : Api()
private abstract class GetPostAPI : Api()
private abstract class GetFileAPI : Api()
private abstract class GetOldAPI : Api()
private abstract class GetServersListAPI : Api()
private abstract class GetTicketsAPI : LoggedInApi()
private abstract class CreateTicketAPI : LoggedInApi()
private abstract class DeleteNotificationAPI : LoggedInApi()
private abstract class SetupStatusAPI : Api()
private abstract class PanelGetPostsAPI : PanelApi()
private abstract class RenderPageAPI
private abstract class GetProductsAPI : Api()
private abstract class GetFaqsAPI : Api()

private object MarketClasses {
    abstract class GetItemsAPI : Api()
}

private object OtherClasses {
    abstract class GetItemsAPI : Api()
}

/** Doc 04 sections 5 and 6: the generator, the shared schemas and the three served documents. */
class OpenApiGeneratorTest {
    // building blocks

    private fun entry(
        declared: String,
        routeClass: Class<*>,
        method: String = "GET",
        pluginId: String? = null,
        mount: Mount = Mount.API,
        namespace: Namespace = Namespace.SITE,
        doc: EndpointDoc? = null,
        stability: Stability? = null,
        deprecation: Deprecation? = null,
        validation: DescribedValidation? = null
    ) = RouteEntry(
        method = method,
        path = ApiPaths.resolve(declared, mount, namespace, pluginId),
        declared = declared,
        pluginId = pluginId,
        routeClass = routeClass,
        mount = mount,
        namespace = namespace,
        doc = doc,
        declaredStability = stability,
        deprecation = deprecation,
        validation = validation
    )

    private fun described(
        parameters: List<ParamSpec> = listOf(),
        bodies: List<BodySpec> = listOf()
    ) = object : DescribedValidation {
        override val parameters = parameters
        override val bodies = bodies
    }

    private fun query(name: String, required: Boolean = false, schema: JsonObject = JsonObject().put("type", "integer")) =
        ParamSpec(ParamLocation.QUERY, name, required, schema)

    private val postDoc = EndpointDoc(
        summary = "One post",
        tag = "posts",
        response = objectSchema().requiredProperty("post", CoreSchemas.post),
        errors = listOf(NotExists::class)
    )

    private val postsDoc = EndpointDoc(summary = "Published posts", paginatedItem = CoreSchemas.postSummary)

    /** A representative table: core public and internal, a plugin with a public and a panel API, a second plugin. */
    private fun table(): List<RouteEntry> = listOf(
        entry(
            "/posts",
            GetPostsAPI::class.java,
            doc = postsDoc,
            validation = described(listOf(query("page"), query("pageSize"), query("categoryUrl", schema = JsonObject().put("type", "string"))))
        ),
        entry("/posts/:url", GetPostAPI::class.java, doc = postDoc),
        entry("/tickets", GetTicketsAPI::class.java, doc = EndpointDoc("My tickets", paginatedItem = CoreSchemas.ticket)),
        entry(
            "/tickets",
            CreateTicketAPI::class.java,
            method = "POST",
            validation = described(
                bodies = listOf(
                    BodySpec(
                        "application/json",
                        SchemaJson.of(objectSchema().requiredProperty("title", stringSchema()).optionalProperty("categoryId", intSchema()))
                    )
                )
            )
        ),
        entry("/notifications/:id", DeleteNotificationAPI::class.java, method = "DELETE"),
        entry("/servers", GetServersListAPI::class.java, doc = EndpointDoc("Servers", response = CoreSchemas.list(CoreSchemas.server))),
        entry("/files/:name", GetFileAPI::class.java, doc = EndpointDoc("A file", binary = true)),
        entry(
            "/old",
            GetOldAPI::class.java,
            deprecation = Deprecation(2, "2027-06-01", "GET /posts")
        ),
        entry("/setup/status", SetupStatusAPI::class.java),
        entry("/posts", PanelGetPostsAPI::class.java, namespace = Namespace.PANEL),
        entry("/page", RenderPageAPI::class.java, mount = Mount.ROOT),
        entry("/anything", GetOldAPI::class.java, method = "ALL"),
        entry("/store/products", GetProductsAPI::class.java, pluginId = "pano-plugin-market", doc = EndpointDoc("Products", paginatedItem = objectSchema().requiredProperty("id", intSchema()))),
        entry("/store/cart", CreateTicketAPI::class.java, method = "POST", pluginId = "pano-plugin-market"),
        entry("/products", PanelGetPostsAPI::class.java, namespace = Namespace.PANEL, pluginId = "pano-plugin-market"),
        entry("/faqs", GetFaqsAPI::class.java, pluginId = "pano-plugin-faq")
    )

    private fun paths(document: JsonObject) = document.getJsonObject("paths").fieldNames().toList()

    private fun operation(document: JsonObject, path: String, method: String) =
        document.getJsonObject("paths").getJsonObject(path).getJsonObject(method)

    // scopes

    @Test
    fun `core holds the public core operations below the api root`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        assertEquals("3.1.0", document.getString("openapi"))
        assertEquals(JsonArray().add(JsonObject().put("url", "/api/v1")), document.getJsonArray("servers"))
        assertEquals(
            listOf("/files/{name}", "/notifications/{id}", "/old", "/posts", "/posts/{url}", "/servers", "/tickets"),
            paths(document)
        )
        assertEquals(listOf("get", "post"), document.getJsonObject("paths").getJsonObject("/tickets").fieldNames().sorted())
    }

    @Test
    fun `a plugin document holds only that plugin's public operations with paths below its server`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Plugin("pano-plugin-market"), table())

        assertEquals("/api/plugins/pano-plugin-market", document.getJsonArray("servers").getJsonObject(0).getString("url"))
        assertEquals(listOf("/store/cart", "/store/products"), paths(document))
        assertEquals("pano-plugin-market", document.getJsonObject("info").getString("x-pano-plugin-id"))
        assertEquals("plugin", document.getJsonObject("info").getString("x-pano-scope"))
    }

    @Test
    fun `only the internal document overrides the server of a plugin operation`() {
        val plugin = OpenApiGenerator.generate(OpenApiGenerator.Scope.Plugin("pano-plugin-market"), table())

        assertEquals("/api/plugins/pano-plugin-market", plugin.getJsonArray("servers").getJsonObject(0).getString("url"))
        paths(plugin).forEach { path ->
            plugin.getJsonObject("paths").getJsonObject(path).forEach { (_, op) -> assertNull((op as JsonObject).getJsonArray("servers")) }
        }

        val internal = OpenApiGenerator.generate(OpenApiGenerator.Scope.Internal, table())
        val panel = operation(internal, "/plugins/pano-plugin-market/panel/products", "get")

        assertEquals("/api", panel.getJsonArray("servers").getJsonObject(0).getString("url"))
        assertNull(operation(internal, "/panel/posts", "get").getJsonArray("servers"))
    }

    private class StandInCatalog : com.panomc.platform.api.ErrorCatalogProvider {
        class EmptyCart : com.panomc.platform.model.Error("EMPTY_CART", 400)
        class OutOfStock(sku: String) : com.panomc.platform.model.Error("OUT_OF_STOCK", 409, "Conflict", mapOf("sku" to sku))

        override val entries: List<() -> com.panomc.platform.model.Error> = listOf(
            { OutOfStock("a") },
            { EmptyCart() },
            { EmptyCart() },
            { error("a broken entry never breaks the document") }
        )
    }

    @Test
    fun `a plugin error catalogue adds its codes to the plugin document`() {
        val document = OpenApiGenerator.generate(
            OpenApiGenerator.Scope.Plugin("pano-plugin-market"),
            table(),
            errorCatalog = StandInCatalog().entries
        )

        assertEquals(
            listOf("EMPTY_CART" to 400, "OUT_OF_STOCK" to 409),
            document.getJsonArray("x-pano-error-catalog").map { (it as JsonObject).getString("code") to it.getInteger("status") }
        )
        assertValidOpenApi(document)
        assertFalse(OpenApiGenerator.generate(OpenApiGenerator.Scope.Plugin("pano-plugin-market"), table()).containsKey("x-pano-error-catalog"))
    }

    @Test
    fun `a plugin without public endpoints gets a valid document with no paths`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Plugin("pano-plugin-nothing"), table())

        assertTrue(document.getJsonObject("paths").isEmpty)
        assertValidOpenApi(document)
    }

    @Test
    fun `the internal document holds every internal operation of core and of plugins`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Internal, table())

        assertEquals("/api/v1", document.getJsonArray("servers").getJsonObject(0).getString("url"))
        assertEquals(listOf("/panel/posts", "/plugins/pano-plugin-market/panel/products", "/setup/status"), paths(document))

        paths(document).forEach { path ->
            document.getJsonObject("paths").getJsonObject(path).forEach { (_, op) ->
                assertEquals("internal", (op as JsonObject).getString("x-pano-stability"))
            }
        }
    }

    @Test
    fun `an explicit stability moves an operation between the documents`() {
        val entries = listOf(
            entry("/plugins/:pluginId/_/ui.zip", GetFileAPI::class.java, stability = Stability.INTERNAL),
            entry("/server/icon/default", GetOldAPI::class.java, stability = Stability.PUBLIC)
        )

        assertEquals(listOf("/server/icon/default"), paths(OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, entries)))
        assertEquals(
            listOf("/plugins/{pluginId}/_/ui.zip"),
            paths(OpenApiGenerator.generate(OpenApiGenerator.Scope.Internal, entries))
        )
    }

    @Test
    fun `routes mounted outside the api and routes for every method are left out`() {
        val everything = paths(OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())) +
            paths(OpenApiGenerator.generate(OpenApiGenerator.Scope.Internal, table()))

        assertFalse(everything.contains("/page"))
        assertFalse(everything.contains("/anything"))
    }

    // info and operations

    @Test
    fun `info version is the api level as a string`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, listOf())

        assertEquals("1", document.getJsonObject("info").getString("version"))
        assertEquals(1, document.getJsonObject("info").getInteger("x-pano-api-level"))
        assertEquals("7", OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, listOf(), apiLevel = 7).getJsonObject("info").getString("version"))
    }

    @Test
    fun `operationId is the class name minus API and the method keys the path item`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        assertEquals("GetPosts", operation(document, "/posts", "get").getString("operationId"))
        assertEquals("GetPost", operation(document, "/posts/{url}", "get").getString("operationId"))
        assertEquals("CreateTicket", operation(document, "/tickets", "post").getString("operationId"))
        assertEquals("DeleteNotification", operation(document, "/notifications/{id}", "delete").getString("operationId"))
    }

    @Test
    fun `operationIds stay unique when a class name repeats`() {
        val entries = listOf(
            entry("/items", MarketClasses.GetItemsAPI::class.java, pluginId = "pano-plugin-market"),
            entry("/items", OtherClasses.GetItemsAPI::class.java, pluginId = "pano-plugin-other"),
            entry("/items", PanelGetPostsAPI::class.java, namespace = Namespace.PANEL),
            entry("/more", PanelGetPostsAPI::class.java, namespace = Namespace.PANEL)
        )

        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Internal, entries.map { it.copy(declaredStability = Stability.INTERNAL) })
        val ids = operationIds(document)

        assertEquals(4, ids.size)
        assertEquals(ids.size, ids.toSet().size, "operationIds must be unique: $ids")
        assertTrue(ids.contains("GetItems"))
        assertTrue(ids.contains("GetItems_pano_plugin_other") || ids.contains("GetItems_pano_plugin_market"))
    }

    @Test
    fun `the tag is the doc tag, else the first segment of the declared path`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        assertEquals(JsonArray().add("posts"), operation(document, "/posts/{url}", "get").getJsonArray("tags"))
        assertEquals(JsonArray().add("tickets"), operation(document, "/tickets", "post").getJsonArray("tags"))
        assertEquals(JsonArray().add("notifications"), operation(document, "/notifications/{id}", "delete").getJsonArray("tags"))
        assertEquals(listOf("files", "notifications", "old", "posts", "servers", "tickets"), document.getJsonArray("tags").map { (it as JsonObject).getString("name") })
    }

    @Test
    fun `an endpoint without doc is undocumented and answers an open object`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        val undocumented = operation(document, "/tickets", "post")

        assertEquals(true, undocumented.getBoolean("x-pano-undocumented"))
        assertEquals(
            JsonObject().put("type", "object").put("additionalProperties", true),
            undocumented.getJsonObject("responses").getJsonObject("200").getJsonObject("content")
                .getJsonObject("application/json").getJsonObject("schema")
        )

        assertNull(operation(document, "/posts/{url}", "get").getBoolean("x-pano-undocumented"))
        assertEquals("One post", operation(document, "/posts/{url}", "get").getString("summary"))
    }

    @Test
    fun `stability is public or internal on every operation`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        assertEquals("public", operation(document, "/posts", "get").getString("x-pano-stability"))
    }

    @Test
    fun `a deprecated endpoint carries the flag, the removal date and the replacement`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())
        val old = operation(document, "/old", "get")

        assertEquals(true, old.getBoolean("deprecated"))
        assertEquals("2027-06-01", old.getString("x-pano-removal"))
        assertEquals("GET /posts", old.getString("x-pano-replacement"))
        assertTrue(old.getString("description").contains("level 2"))

        val headers = old.getJsonObject("responses").getJsonObject("200").getJsonObject("headers")

        assertTrue(headers.containsKey("Deprecation"))
        assertTrue(headers.containsKey("Sunset"))
        assertNull(operation(document, "/posts", "get").getBoolean("deprecated"))
        assertFalse(operation(document, "/posts", "get").getJsonObject("responses").getJsonObject("200").getJsonObject("headers").containsKey("Sunset"))
    }

    // parameters and bodies

    @Test
    fun `every path placeholder is a required path parameter, recorded or not`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        val parameters = operation(document, "/posts/{url}", "get").getJsonArray("parameters")

        assertEquals(1, parameters.size())
        assertEquals("url", parameters.getJsonObject(0).getString("name"))
        assertEquals("path", parameters.getJsonObject(0).getString("in"))
        assertEquals(true, parameters.getJsonObject(0).getBoolean("required"))
        assertEquals("string", parameters.getJsonObject(0).getJsonObject("schema").getString("type"))
    }

    @Test
    fun `recorded query parameters keep their required flag and schema`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        val parameters = operation(document, "/posts", "get").getJsonArray("parameters").map { it as JsonObject }

        assertEquals(listOf("page", "pageSize", "categoryUrl"), parameters.map { it.getString("name") })
        assertTrue(parameters.all { it.getString("in") == "query" && it.getBoolean("required") == false })
        assertEquals("string", parameters[2].getJsonObject("schema").getString("type"))
    }

    @Test
    fun `a recorded path parameter keeps its schema and a stale one is dropped`() {
        val entries = listOf(
            entry(
                "/tickets/:id",
                GetPostAPI::class.java,
                validation = described(
                    listOf(
                        ParamSpec(ParamLocation.PATH, "id", true, JsonObject().put("type", "integer")),
                        ParamSpec(ParamLocation.PATH, "gone", true, JsonObject().put("type", "string")),
                        ParamSpec(ParamLocation.HEADER, "X-Thing", false, JsonObject().put("type", "string"))
                    )
                )
            )
        )

        val parameters = operation(OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, entries), "/tickets/{id}", "get")
            .getJsonArray("parameters").map { it as JsonObject }

        assertEquals(listOf("id" to "path", "X-Thing" to "header"), parameters.map { it.getString("name") to it.getString("in") })
        assertEquals("integer", parameters[0].getJsonObject("schema").getString("type"))
    }

    @Test
    fun `a recorded body becomes a required request body`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        val body = operation(document, "/tickets", "post").getJsonObject("requestBody")

        assertEquals(true, body.getBoolean("required"))
        assertEquals(
            listOf("title"),
            body.getJsonObject("content").getJsonObject("application/json").getJsonObject("schema").getJsonArray("required").toList()
        )
        assertNull(operation(document, "/posts", "get").getJsonObject("requestBody"))
    }

    @Test
    fun `the real validation handlers of two core endpoints reach the document`() {
        val repository = SchemaRepository.create(JsonSchemaOptions().setBaseUri("https://panomc.com").setDraft(Draft.DRAFT7))
        val objenesis = ObjenesisStd()

        fun validationOf(type: Class<out Api>) = objenesis.newInstance(type).getValidationHandler(repository) as DescribedValidation

        val entries = listOf(
            entry("/posts", GetPostsAPI::class.java, validation = validationOf(CoreGetPostsAPI::class.java)),
            entry("/posts/:url", GetPostAPI::class.java, validation = validationOf(GetPostDetailAPI::class.java))
        )

        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, entries)

        assertEquals(
            listOf("page", "pageSize", "categoryUrl"),
            operation(document, "/posts", "get").getJsonArray("parameters").map { (it as JsonObject).getString("name") }
        )
        assertEquals("url", operation(document, "/posts/{url}", "get").getJsonArray("parameters").getJsonObject(0).getString("name"))
        assertValidOpenApi(document)
    }

    // responses and components

    @Test
    fun `a doc response uses the shared schemas as components`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        val schema = operation(document, "/posts/{url}", "get").getJsonObject("responses").getJsonObject("200")
            .getJsonObject("content").getJsonObject("application/json").getJsonObject("schema")

        assertEquals("#/components/schemas/Post", schema.getJsonObject("properties").getJsonObject("post").getString("\$ref"))

        val components = document.getJsonObject("components").getJsonObject("schemas")

        assertEquals(listOf("Error", "Page", "Post", "PostSummary", "Server", "Ticket"), components.fieldNames().sorted())
        assertEquals("object", components.getJsonObject("Post").getString("type"))
        assertFalse(components.getJsonObject("Post").containsKey("\$id"))
    }

    @Test
    fun `a list endpoint answers items and the shared page object`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        val schema = operation(document, "/posts", "get").getJsonObject("responses").getJsonObject("200")
            .getJsonObject("content").getJsonObject("application/json").getJsonObject("schema")

        assertEquals(JsonArray().add("items").add("page"), schema.getJsonArray("required"))
        assertEquals(
            JsonObject().put("type", "array").put("items", JsonObject().put("\$ref", "#/components/schemas/PostSummary")),
            schema.getJsonObject("properties").getJsonObject("items")
        )
        assertEquals("#/components/schemas/Page", schema.getJsonObject("properties").getJsonObject("page").getString("\$ref"))

        val page = document.getJsonObject("components").getJsonObject("schemas").getJsonObject("Page")

        assertEquals(listOf("number", "size", "totalItems", "totalPages"), page.getJsonArray("required").toList())
    }

    @Test
    fun `a binary answer is an octet stream`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        val content = operation(document, "/files/{name}", "get").getJsonObject("responses").getJsonObject("200").getJsonObject("content")

        assertEquals(listOf("application/octet-stream"), content.fieldNames().toList())
        assertEquals("binary", content.getJsonObject("application/octet-stream").getJsonObject("schema").getString("format"))
    }

    @Test
    fun `the error envelope is one component and the common codes are shared responses`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())
        val components = document.getJsonObject("components")

        val error = components.getJsonObject("schemas").getJsonObject("Error")
        val inner = error.getJsonObject("properties").getJsonObject("error")

        assertEquals(JsonArray().add("error"), error.getJsonArray("required"))
        assertEquals(listOf("code", "details", "fields", "message"), inner.getJsonObject("properties").fieldNames().sorted())
        assertEquals(JsonArray().add("code"), inner.getJsonArray("required"))

        val shared = components.getJsonObject("responses")
        val codes = shared.fieldNames().sorted().flatMap { shared.getJsonObject(it).getJsonArray("x-pano-error-codes").map { c -> c.toString() } }.sorted()

        assertEquals(
            listOf(
                "BAD_REQUEST", "INTERNAL_SERVER_ERROR", "INVALID_CSRF_TOKEN", "MAINTENANCE_MODE_ENABLED",
                "NOT_LOGGED_IN", "NO_PERMISSION", "RATE_LIMIT_EXCEEDED"
            ),
            codes
        )
        assertEquals(listOf("Error400", "Error401", "Error403", "Error429", "Error500", "Error503"), shared.fieldNames().sorted())

        shared.forEach { (_, response) ->
            assertEquals(
                "#/components/schemas/Error",
                (response as JsonObject).getJsonObject("content").getJsonObject("application/json").getJsonObject("schema").getString("\$ref")
            )
        }
    }

    @Test
    fun `an operation refers to the common responses it can answer`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        val anonymousRead = operation(document, "/servers", "get").getJsonObject("responses")

        assertEquals(listOf("200", "429", "500", "503"), anonymousRead.fieldNames().sorted())
        assertEquals("#/components/responses/Error429", anonymousRead.getJsonObject("429").getString("\$ref"))

        val loggedInWrite = operation(document, "/tickets", "post").getJsonObject("responses")

        assertEquals(listOf("200", "400", "401", "403", "429", "500", "503"), loggedInWrite.fieldNames().sorted())
        assertEquals("#/components/responses/Error403", loggedInWrite.getJsonObject("403").getString("\$ref"))
        assertEquals("#/components/responses/Error400", loggedInWrite.getJsonObject("400").getString("\$ref"))
    }

    @Test
    fun `a read by a logged in user lists no csrf code`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        val forbidden = operation(document, "/tickets", "get").getJsonObject("responses").getJsonObject("403")

        assertEquals(JsonArray().add("NO_PERMISSION"), forbidden.getJsonArray("x-pano-error-codes"))
    }

    @Test
    fun `declared errors are listed under their status`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        val notFound = operation(document, "/posts/{url}", "get").getJsonObject("responses").getJsonObject("404")

        assertEquals(JsonArray().add("NOT_EXISTS"), notFound.getJsonArray("x-pano-error-codes"))
        assertTrue(notFound.getString("description").startsWith("Not found"))
    }

    @Test
    fun `a declared error on a status with common codes adds to them`() {
        val entries = listOf(
            entry("/secret", GetTicketsAPI::class.java, doc = EndpointDoc("Secret", errors = listOf(NoPermission::class)))
        )

        val forbidden = operation(OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, entries), "/secret", "get")
            .getJsonObject("responses").getJsonObject("403")

        assertEquals(JsonArray().add("NO_PERMISSION"), forbidden.getJsonArray("x-pano-error-codes"))
    }

    @Test
    fun `login operations name their security and the panel takes the cookie only`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())
        val panel = OpenApiGenerator.generate(OpenApiGenerator.Scope.Internal, table())

        assertEquals(2, operation(document, "/tickets", "get").getJsonArray("security").size())
        assertNull(operation(document, "/servers", "get").getJsonArray("security"))
        assertEquals(1, operation(panel, "/panel/posts", "get").getJsonArray("security").size())

        val schemes = document.getJsonObject("components").getJsonObject("securitySchemes")

        assertEquals(listOf("bearerAuth", "sessionCookie"), schemes.fieldNames().sorted())
        assertEquals("cookie", schemes.getJsonObject("sessionCookie").getString("in"))
    }

    @Test
    fun `two different schemas with one pano id keep the first as the component and inline the second`() {
        val first = EndpointDoc("A", response = objectSchema().requiredProperty("a", CoreSchemas.shape("Thing").requiredProperty("x", intSchema())))
        val second = EndpointDoc("B", response = objectSchema().requiredProperty("b", CoreSchemas.shape("Thing").requiredProperty("y", stringSchema())))
        val same = EndpointDoc("C", response = objectSchema().requiredProperty("c", CoreSchemas.shape("Thing").requiredProperty("x", intSchema())))

        val document = OpenApiGenerator.generate(
            OpenApiGenerator.Scope.Core,
            listOf(
                entry("/a", GetPostsAPI::class.java, doc = first),
                entry("/b", GetPostAPI::class.java, doc = second),
                entry("/c", GetFileAPI::class.java, doc = same)
            )
        )

        fun property(path: String, name: String) = operation(document, path, "get").getJsonObject("responses").getJsonObject("200")
            .getJsonObject("content").getJsonObject("application/json").getJsonObject("schema").getJsonObject("properties").getJsonObject(name)

        assertEquals("#/components/schemas/Thing", property("/a", "a").getString("\$ref"))
        assertEquals("#/components/schemas/Thing", property("/c", "c").getString("\$ref"))
        assertEquals("object", property("/b", "b").getString("type"))
        assertNull(property("/b", "b").getString("\$ref"))
        assertEquals(listOf("x"), document.getJsonObject("components").getJsonObject("schemas").getJsonObject("Thing").getJsonObject("properties").fieldNames().toList())
    }

    // determinism

    @Test
    fun `the same entries give the same bytes in any order`() {
        val forward = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table()).encodePrettily()
        val backward = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table().reversed()).encodePrettily()
        val again = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table()).encodePrettily()

        assertEquals(forward, backward)
        assertEquals(forward, again)
        assertFalse(forward.contains("urn:vertxschemas"), "generated schema ids must not leak into the document")
    }

    // validity against the OpenAPI 3.1 meta-schema

    @Test
    fun `every scope validates against the OpenAPI 3_1 schema`() {
        listOf(
            OpenApiGenerator.Scope.Core,
            OpenApiGenerator.Scope.Plugin("pano-plugin-market"),
            OpenApiGenerator.Scope.Plugin("pano-plugin-faq"),
            OpenApiGenerator.Scope.Internal
        ).forEach { scope ->
            assertValidOpenApi(OpenApiGenerator.generate(scope, table()), "scope $scope")
        }
    }

    @Test
    fun `an empty route table is still a valid document`() {
        assertValidOpenApi(OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, listOf()))
    }

    @Test
    fun `the meta-schema check really rejects a broken document`() {
        val good = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        assertTrue(metaSchema.validate(good).valid == true)

        val noVersion = good.copy().also { it.getJsonObject("info").remove("version") }
        val badPath = good.copy().also { it.getJsonObject("paths").put("no-slash", JsonObject()) }
        val badParameter = good.copy().also {
            it.getJsonObject("paths").getJsonObject("/posts/{url}").getJsonObject("get")
                .getJsonArray("parameters").getJsonObject(0).put("required", false)
        }
        val unknownKey = good.copy().also { it.getJsonObject("paths").getJsonObject("/posts").getJsonObject("get").put("bogus", 1) }

        assertFalse(metaSchema.validate(noVersion).valid == true)
        assertFalse(metaSchema.validate(badPath).valid == true)
        assertFalse(metaSchema.validate(badParameter).valid == true)
        assertFalse(metaSchema.validate(unknownKey).valid == true)
        assertTrue(metaSchema.validate(good.copy().also { it.getJsonObject("paths").getJsonObject("/posts").getJsonObject("get").put("x-anything", 1) }).valid == true)
    }

    // shared schemas

    @Test
    fun `core schema ids are pano names`() {
        assertEquals("Post", CoreSchemas.componentName("pano:Post"))
        assertEquals("Post", CoreSchemas.componentName("pano:Post#"))
        assertNull(CoreSchemas.componentName("urn:vertxschemas:abc"))
        assertNull(CoreSchemas.componentName("pano:"))
        assertNull(CoreSchemas.componentName(null))

        mapOf(
            "Post" to CoreSchemas.post,
            "PostSummary" to CoreSchemas.postSummary,
            "User" to CoreSchemas.user,
            "Ticket" to CoreSchemas.ticket,
            "Notification" to CoreSchemas.notification,
            "Server" to CoreSchemas.server
        ).forEach { (name, shape) ->
            val json = SchemaJson.of(shape)

            assertEquals(name, CoreSchemas.componentName(json.getString("\$id")), name)
            assertEquals("object", json.getString("type"), name)
            assertTrue(json.getJsonObject("properties").getJsonObject("id") != null || name == "User", name)
        }
    }

    @Test
    fun `core schemas are open objects that never declare error or result`() {
        listOf(CoreSchemas.post, CoreSchemas.postSummary, CoreSchemas.user, CoreSchemas.ticket, CoreSchemas.notification, CoreSchemas.server)
            .forEach { shape ->
                val json = SchemaJson.of(shape)

                assertNull(json.getValue("additionalProperties"), "a response shape must stay open")
                assertFalse(json.getJsonObject("properties").containsKey("error"))
                assertFalse(json.getJsonObject("properties").containsKey("result"))
            }
    }

    @Test
    fun `a post list item written by the real service matches postSummary`() {
        val post = Post(id = 4, title = "Hello", writerUserId = 1, text = "<p>Body</p>", thumbnailUrl = "/t.png", url = "hello")

        val body = wire(GetPostsService.payload(null, listOf(post), mapOf(1L to "steve"), mapOf(), 1, PageRequest(1, 5)))

        assertMatches(CoreSchemas.postSummary, body.getJsonArray("items").getJsonObject(0))

        val page = body.getJsonObject("page")

        assertEquals(listOf("number", "size", "totalItems", "totalPages"), page.fieldNames().sorted())
    }

    @Test
    fun `a ticket list item written by the real service matches ticket`() {
        val ticket = Ticket(id = 3, title = "Help", userId = 1)

        val body = wire(GetTicketsService.payload(null, listOf(ticket), mapOf(), "steve", 1, PageRequest(1, 10)))

        assertMatches(CoreSchemas.ticket, body.getJsonArray("items").getJsonObject(0))
    }

    @Test
    fun `a server written by the real endpoint matches server and leaks no host`() {
        val server = Server(
            id = 2,
            name = "survival",
            motd = "Welcome",
            host = "10.0.0.5",
            port = 25565,
            playerCount = 3,
            maxPlayerCount = 20,
            type = ServerType.PAPER,
            version = "1.21.8",
            favicon = "",
            permissionGranted = true,
            status = ServerStatus.ONLINE,
            startTime = 0,
            aesKey = "secret",
            kind = ServerKind.LINKED
        )

        val body = wire(GetServersAPI.payload(listOf(server), 2L, "play.example.com"))
        val item = body.getJsonArray("items").getJsonObject(0)

        assertMatches(CoreSchemas.server, item)
        assertNull(item.getValue("iconUrl"))
        assertFalse(item.containsKey("host") || item.containsKey("port") || item.containsKey("aesKey"))
    }

    @Test
    fun `a notification written by the real endpoint matches notification`() {
        val notification = Notification(id = 9, userId = 1, type = SampleNotification())

        val body = wire(GetNotificationsAPI.payload(listOf(notification), 1, { null }, 1, PageRequest(1, 10)))

        assertMatches(CoreSchemas.notification, body.getJsonArray("items").getJsonObject(0))
    }

    @Test
    fun `a user matches with and without a last login`() {
        assertMatches(CoreSchemas.user, JsonObject().put("registerDate", 1700000000000))
        assertMatches(CoreSchemas.user, JsonObject().put("registerDate", 1700000000000).put("lastLoginDate", 1700000001000))
    }

    @Test
    fun `a shape rejects a payload that misses a required property`() {
        val doc = EndpointDoc("x", response = objectSchema().requiredProperty("item", CoreSchemas.server))

        val failures = doc.check(JsonObject().put("item", JsonObject().put("id", 1)))

        assertTrue(failures.isNotEmpty(), "a server without name must not match")
    }

    // the three served documents

    @Test
    fun `the three openapi endpoints are listed in the document they serve`() {
        val entries = listOf(
            entry("/openapi.json", GetOpenApiAPI::class.java, doc = EndpointDoc("core", response = objectSchema().requiredProperty("openapi", stringSchema()))),
            entry("/plugins/:pluginId/_/openapi.json", GetPluginOpenApiAPI::class.java),
            entry("/openapi.json", PanelGetOpenApiAPI::class.java, namespace = Namespace.PANEL)
        )

        assertEquals(
            listOf("/openapi.json", "/plugins/{pluginId}/_/openapi.json"),
            paths(OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, entries))
        )
        assertEquals(listOf("/panel/openapi.json"), paths(OpenApiGenerator.generate(OpenApiGenerator.Scope.Internal, entries)))
    }

    @Test
    fun `the plugin document is refused for a plugin that is unknown or not started`() {
        GetPluginOpenApiAPI.requireStarted(PluginState.STARTED)

        listOf(null, PluginState.STOPPED, PluginState.DISABLED, PluginState.RESOLVED).forEach { state ->
            val failure = runCatching { GetPluginOpenApiAPI.requireStarted(state) }.exceptionOrNull()

            assertNotNull(failure, "state $state must give 404")
            assertEquals(404, (failure as com.panomc.platform.model.Error).getStatusCode())
            assertEquals("NOT_FOUND", failure.code)
        }
    }

    @Test
    fun `the served response shape is declared and usable`() {
        val document = OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, table())

        listOf(GetOpenApiAPI(RouteTable()).doc, PanelGetOpenApiAPI(RouteTable()).doc).forEach { doc ->
            assertNull(doc.problem())
            assertEquals(listOf<String>(), doc.check(document))
        }
    }

    // sitemap is a core public endpoint with a page shape; its operation id comes out as the class name

    @Test
    fun `a real core endpoint class gets its operation id from its name`() {
        val document = OpenApiGenerator.generate(
            OpenApiGenerator.Scope.Core,
            listOf(entry("/sitemap", GetSitemapAPI::class.java))
        )

        assertEquals("GetSitemap", operation(document, "/sitemap", "get").getString("operationId"))
    }

    // helpers

    private class SampleNotification : NotificationType

    /** A body as it leaves the server: encoded and parsed again, so an enum is its name. */
    private fun wire(body: Map<String, Any?>) = JsonObject(JsonObject(body).encode())

    private fun operationIds(document: JsonObject): List<String> =
        document.getJsonObject("paths").map { (_, item) -> (item as JsonObject).map { (_, op) -> (op as JsonObject).getString("operationId") } }.flatten()

    /** The schema, built the way a response is, matches [value]; otherwise the failing pointers. */
    private fun assertMatches(shape: io.vertx.json.schema.common.dsl.SchemaBuilder<*, *>, value: JsonObject) {
        val failures = EndpointDoc("x", response = objectSchema().requiredProperty("item", shape)).check(JsonObject().put("item", value))

        assertEquals(listOf<String>(), failures, "payload $value")
    }

    private fun messages(unit: OutputUnit) = unit.errors.orEmpty().map { "${it.instanceLocation}: ${it.error}" }.distinct()

    private fun assertValidOpenApi(document: JsonObject, label: String = "document") {
        val checked = JsonObject(document.encode())

        val result = metaSchema.validate(checked)

        assertTrue(result.valid == true, "$label is not valid OpenAPI 3.1:\n${messages(result).joinToString("\n")}")

        // Schema Objects are checked as objects or booleans by the OpenAPI schema; here they are checked as JSON Schema.
        schemasOf(checked).forEach { (where, schema) ->
            val unit = jsonSchemaMeta.validate(schema)

            assertTrue(unit.valid == true, "$label: schema at $where is not valid JSON Schema 2020-12:\n${messages(unit).joinToString("\n")}")
        }

        assertEquals(setOf<String>(), unresolvedReferences(checked), "$label has dangling \$ref")
        assertEquals(listOf<String>(), idsIn(checked), "$label must not contain any \$id")

        val ids = operationIds(checked)

        assertEquals(ids.size, ids.toSet().size, "$label: operationIds must be unique")
    }

    private fun schemasOf(document: JsonObject): List<Pair<String, JsonObject>> {
        val found = mutableListOf<Pair<String, JsonObject>>()

        document.getJsonObject("components")?.getJsonObject("schemas")?.forEach { (name, schema) ->
            found.add("#/components/schemas/$name" to schema as JsonObject)
        }

        fun content(where: String, content: JsonObject?) = content?.forEach { (type, media) ->
            (media as JsonObject).getJsonObject("schema")?.let { found.add("$where/$type" to it) }
        }

        document.getJsonObject("paths").forEach { (path, item) ->
            (item as JsonObject).forEach { (method, op) ->
                val operation = op as JsonObject

                operation.getJsonArray("parameters")?.forEachIndexed { index, parameter ->
                    (parameter as JsonObject).getJsonObject("schema")?.let { found.add("$path $method parameter $index" to it) }
                }

                content("$path $method requestBody", operation.getJsonObject("requestBody")?.getJsonObject("content"))

                operation.getJsonObject("responses").forEach { (status, response) ->
                    content("$path $method $status", (response as JsonObject).getJsonObject("content"))
                }
            }
        }

        return found
    }

    private fun unresolvedReferences(document: JsonObject): Set<String> {
        val missing = mutableSetOf<String>()

        fun walk(node: Any?) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    if (key == "\$ref" && value is String && value.startsWith("#/")) {
                        var target: Any? = document

                        value.removePrefix("#/").split('/').forEach { part ->
                            target = (target as? JsonObject)?.getValue(part)
                        }

                        if (target == null) {
                            missing.add(value)
                        }
                    }

                    walk(value)
                }

                is JsonArray -> node.forEach { walk(it) }
            }
        }

        walk(document)

        return missing
    }

    private fun idsIn(node: Any?): List<String> = when (node) {
        is JsonObject -> node.flatMap { (key, value) -> (if (key == "\$id") listOf("\$id=$value") else listOf()) + idsIn(value) }
        is JsonArray -> node.flatMap { idsIn(it) }
        else -> listOf()
    }

    private val metaSchema: Validator by lazy {
        val text = OpenApiGeneratorTest::class.java.getResourceAsStream("/openapi/openapi-3.1-schema.json")!!.use { String(it.readAllBytes()) }

        Validator.create(
            JsonSchema.of(JsonObject(text)),
            JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("app://openapi").setOutputFormat(OutputFormat.Basic)
        )
    }

    /** The JSON Schema 2020-12 meta-schema, from the copies the Vert.x library bundles. */
    private val jsonSchemaMeta: Validator by lazy {
        val base = "https://json-schema.org/draft/2020-12/"

        val repository = SchemaRepository.create(
            JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("app://meta").setOutputFormat(OutputFormat.Basic)
        )

        listOf(
            "schema", "meta/core", "meta/applicator", "meta/unevaluated", "meta/validation",
            "meta/meta-data", "meta/format-annotation", "meta/content"
        ).forEach { name ->
            val text = SchemaRepository::class.java.classLoader.getResourceAsStream("json-schema.org/draft/2020-12/$name")!!
                .use { String(it.readAllBytes()) }

            repository.dereference("$base$name", JsonSchema.of("$base$name", JsonObject(text)))
        }

        repository.validator("${base}schema")
    }
}
