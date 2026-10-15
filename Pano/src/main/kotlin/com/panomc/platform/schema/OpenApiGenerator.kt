package com.panomc.platform.schema

import com.panomc.platform.api.ErrorStandIn
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.InternalServerError
import com.panomc.platform.error.InvalidCsrfToken
import com.panomc.platform.error.MaintenanceModeEnabled
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.error.RateLimitExceeded
import com.panomc.platform.model.Error
import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.PanelApi
import com.panomc.platform.route.ApiPaths
import com.panomc.platform.route.Mount
import com.panomc.platform.route.RouteEntry
import com.panomc.platform.schema.dsl.ParamLocation
import com.panomc.platform.ui.SupportedApiLevel
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Turns the route table into an OpenAPI 3.1 document (doc 04 section 5). Pure: the same entries always give the
 * same document, byte for byte once encoded, so a committed snapshot only changes when the API does.
 *
 * Three scopes, one per served document:
 * - [Scope.Core]: the public operations of core, server `/api/v1`.
 * - [Scope.Plugin]: the public operations of one plugin, server `/api/plugins/<id>`, paths relative to it.
 * - [Scope.Internal]: every internal operation of core and of all plugins (panel, setup, node), server `/api/v1`;
 *   a plugin's panel operations (`/api/plugins/<id>/panel/...`) are listed as `/plugins/<id>/panel/...` with an operation-level `servers` override of `/api`.
 *
 * What an operation carries:
 * - `operationId`: the endpoint class name minus `API`. A name taken twice in one document (two plugins, or one class
 *   with several paths) gets the plugin id, then a number, so ids stay unique.
 * - `x-pano-stability` (`public` | `internal`), `deprecated` with `x-pano-removal` (ISO date) and
 *   `x-pano-replacement`, and `x-pano-undocumented: true` when the endpoint declares no `doc`.
 * - Parameters and body from the validation handler's recording ([RouteEntry.validation]); every `:name` of the path
 *   is a path parameter even when nothing recorded it.
 * - The success response from [EndpointDoc], else an open object. Error responses name their codes in
 *   `x-pano-error-codes`; one shared `Error` envelope component, and the common codes as `components.responses`.
 * - A schema with the `$id` `pano:<Name>` (see [CoreSchemas]) becomes `components.schemas.<Name>`. Every other `$id` is
 *   dropped, because a nested `$ref` must resolve against the document, not against an id.
 */
object OpenApiGenerator {
    const val OPENAPI_VERSION = "3.1.0"

    /** Which of the three served documents to build. */
    sealed interface Scope {
        /** The public operations of core. */
        object Core : Scope {
            override fun toString() = "Core"
        }

        /** The public operations of the plugin [id]. */
        data class Plugin(val id: String) : Scope

        /** All internal operations, of core and of every plugin. */
        object Internal : Scope {
            override fun toString() = "Internal"
        }
    }

    /**
     * Builds the document of [scope] from [entries]. [apiLevel] is `info.version`; it is the level this Pano
     * implements, [SupportedApiLevel.current] unless a test passes another.
     */
    fun generate(
        scope: Scope,
        entries: List<RouteEntry>,
        apiLevel: Int = SupportedApiLevel.current,
        errorCatalog: List<() -> Error> = listOf()
    ): JsonObject {
        val serverUrl = serverUrl(scope)
        val ctx = Context()

        val selected = entries
            .asSequence()
            .filter { it.mount == Mount.API && it.method in METHODS }
            .filter { inScope(scope, it) }
            .mapNotNull { entry -> relativePath(scope, entry.path, serverUrl)?.let { Selected(entry, it) } }
            .sortedWith(compareBy<Selected>({ it.path }, { METHODS.indexOf(it.entry.method) }))
            .toList()

        val ids = OperationIds()
        val paths = linkedMapOf<String, JsonObject>()
        val tags = sortedSetOf<String>()

        selected.forEach { (entry, path) ->
            val operation = operation(entry, path, ids.allocate(entry), ctx)

            tags.addAll(operation.getJsonArray("tags").map { it.toString() })

            // A plugin's panel operation sits outside the document's server (`/api/plugins/<id>/panel/...`, decision 81):
            // its `servers` override says `/api`, the key carries the rest. Only the internal document does this: a public
            // plugin document keeps its own server (`/api/plugins/<id>`) and plain relative keys.
            if (scope == Scope.Internal && isOutsideRoot(entry.path)) {
                operation.put("servers", JsonArray().add(JsonObject().put("url", ApiPaths.BASE)))
            }

            paths.getOrPut(templated(path)) { JsonObject() }.put(entry.method.lowercase(), operation)
        }

        val pathsJson = JsonObject()
        paths.forEach { (path, item) -> pathsJson.put(path, item) }

        val document = JsonObject()
            .put("openapi", OPENAPI_VERSION)
            .put("info", info(scope, apiLevel))
            .put("servers", JsonArray().add(JsonObject().put("url", serverUrl)))
            .put("tags", JsonArray(tags.map { JsonObject().put("name", it) }))
            .put("paths", pathsJson)
            .put("components", components(ctx))

        catalog(errorCatalog)?.let { document.put("x-pano-error-catalog", it) }

        return document
    }

    /**
     * The codes a plugin lists through its `ErrorCatalogProvider` beans, as `[{ code, status }]` sorted by code and
     * without duplicates; `null` when there are none. An entry that throws or cannot be read is skipped.
     */
    private fun catalog(entries: List<() -> Error>): JsonArray? {
        val byCode = sortedMapOf<String, Int>()

        entries.forEach { build ->
            val error = runCatching(build).getOrNull() ?: return@forEach

            byCode.putIfAbsent(error.code, error.getStatusCode())
        }

        if (byCode.isEmpty()) {
            return null
        }

        return JsonArray(byCode.map { (code, status) -> JsonObject().put("code", code).put("status", status) })
    }

    // scope

    private val METHODS = listOf("GET", "PUT", "POST", "DELETE", "PATCH")

    private fun serverUrl(scope: Scope): String = when (scope) {
        is Scope.Plugin -> "${ApiPaths.PLUGINS_ROOT}/${scope.id}"
        else -> ApiPaths.ROOT
    }

    private fun inScope(scope: Scope, entry: RouteEntry): Boolean = when (scope) {
        Scope.Core -> entry.pluginId == null && entry.stability == Stability.PUBLIC
        is Scope.Plugin -> entry.pluginId == scope.id && entry.stability == Stability.PUBLIC
        Scope.Internal -> entry.stability == Stability.INTERNAL
    }

    /** [path] below [serverUrl] (`/api/v1/posts` gives `/posts`), or `null` when it is not below it. */
    private fun relativePath(scope: Scope, path: String, serverUrl: String): String? = when {
        path.startsWith("$serverUrl/") -> path.removePrefix(serverUrl)

        // A plugin's panel operations are internal and live outside the core version prefix
        // (`/api/plugins/<id>/panel/...`, decision 81): the internal document lists them as
        // `/plugins/<id>/panel/...` and each operation carries a `servers` override of `/api`.
        scope == Scope.Internal && path.startsWith("${ApiPaths.PLUGINS_ROOT}/") -> path.removePrefix(ApiPaths.BASE)

        else -> null
    }

    private fun isOutsideRoot(entryPath: String) = entryPath.startsWith("${ApiPaths.PLUGINS_ROOT}/")

    private val PARAMETER = Regex(":([A-Za-z0-9_]+)")

    /** `/posts/:url` as the OpenAPI template `/posts/{url}`. */
    private fun templated(path: String) = PARAMETER.replace(path) { "{${it.groupValues[1]}}" }

    private fun pathParameterNames(path: String) = PARAMETER.findAll(path).map { it.groupValues[1] }.distinct().toList()

    private data class Selected(val entry: RouteEntry, val path: String)

    private fun info(scope: Scope, apiLevel: Int): JsonObject {
        val (title, description) = when (scope) {
            Scope.Core -> "Pano API" to "The public API of a Pano site. Operations here are additive-only: nothing is removed or renamed, and no response field changes type or disappears."
            is Scope.Plugin -> "Pano plugin API: ${scope.id}" to "The public API of the plugin ${scope.id}, served below /api/plugins/${scope.id}."
            Scope.Internal -> "Pano internal API" to "The panel, setup, node and server protocols of this Pano site and its plugins. Internal operations carry no compatibility promise: the panel and the platform ship pinned together."
        }

        val info = JsonObject()
            .put("title", title)
            .put("summary", title)
            .put("description", description)
            .put("version", apiLevel.toString())
            .put("x-pano-api-level", apiLevel)

        when (scope) {
            Scope.Core -> info.put("x-pano-scope", "core")
            is Scope.Plugin -> info.put("x-pano-scope", "plugin").put("x-pano-plugin-id", scope.id)
            Scope.Internal -> info.put("x-pano-scope", "internal")
        }

        return info
    }

    // operations

    private class Context {
        val schemas = sortedMapOf<String, JsonObject>(
            "Error" to ERROR_SCHEMA.copy(),
            "Page" to PAGE_SCHEMA.copy()
        )
        val securitySchemes = sortedSetOf<String>()
    }

    private fun operation(entry: RouteEntry, path: String, operationId: String, ctx: Context): JsonObject {
        val doc = entry.doc
        val mutation = entry.method != "GET"
        val loggedIn = LoggedInApi::class.java.isAssignableFrom(entry.routeClass)
        val panel = PanelApi::class.java.isAssignableFrom(entry.routeClass)

        val operation = JsonObject()
            .put("operationId", operationId)
            .put("tags", JsonArray().add(doc?.tag ?: tagOf(entry.declared)))

        doc?.summary?.takeIf { it.isNotBlank() }?.let { operation.put("summary", it) }

        entry.deprecation?.let { deprecation ->
            operation
                .put("description", deprecationText(deprecation))
                .put("deprecated", true)
        }

        val parameters = parameters(entry, path, ctx)

        if (!parameters.isEmpty) {
            operation.put("parameters", parameters)
        }

        val body = requestBody(entry, ctx)

        if (body != null) {
            operation.put("requestBody", body)
        }

        if (loggedIn) {
            val requirements = JsonArray().add(JsonObject().put(COOKIE_SCHEME, JsonArray()))

            ctx.securitySchemes.add(COOKIE_SCHEME)

            // A site session token is never valid on the panel API (doc 05 section 3.3).
            if (!panel) {
                requirements.add(JsonObject().put(BEARER_SCHEME, JsonArray()))
                ctx.securitySchemes.add(BEARER_SCHEME)
            }

            operation.put("security", requirements)
        }

        operation.put("responses", responses(entry, doc, parameters, body != null, loggedIn, panel, mutation, ctx))
        operation.put("x-pano-stability", entry.stability.name.lowercase())

        entry.deprecation?.let { deprecation ->
            operation.put("x-pano-removal", deprecation.removeAfter)
            deprecation.replacement?.let { operation.put("x-pano-replacement", it) }
        }

        if (doc == null) {
            operation.put("x-pano-undocumented", true)
        }

        return operation
    }

    /** The first segment of the declared path without parameters: `/posts/:url` gives `posts`. */
    private fun tagOf(declared: String): String {
        val first = declared.trim('/').substringBefore('/')

        return if (first.isEmpty() || first.startsWith(":")) "default" else first
    }

    private fun deprecationText(deprecation: Deprecation): String = buildString {
        append("Deprecated since API level ${deprecation.sinceLevel}; removed after ${deprecation.removeAfter}.")

        deprecation.replacement?.let { append(" Use $it.") }
    }

    private fun parameters(entry: RouteEntry, path: String, ctx: Context): JsonArray {
        val inPath = pathParameterNames(path)
        val recorded = entry.validation?.parameters.orEmpty()
        val seen = mutableSetOf<String>()
        val result = JsonArray()

        fun add(location: String, name: String, required: Boolean, schema: JsonObject) {
            if (!seen.add("$location:$name")) {
                return
            }

            result.add(
                JsonObject()
                    .put("name", name)
                    .put("in", location)
                    .put("required", required)
                    .put("schema", schemaOf(schema, ctx))
            )
        }

        // Path parameters first, in the order of the path; every `:name` is declared, recorded or not.
        inPath.forEach { name ->
            val spec = recorded.firstOrNull { it.location == ParamLocation.PATH && it.name == name }

            add("path", name, true, spec?.schemaJson ?: JsonObject().put("type", "string"))
        }

        recorded
            .filter { it.location != ParamLocation.PATH }
            .forEach { add(it.location.openApi, it.name, it.required, it.schemaJson) }

        return result
    }

    private fun requestBody(entry: RouteEntry, ctx: Context): JsonObject? {
        val bodies = entry.validation?.bodies.orEmpty()

        if (bodies.isEmpty()) {
            return null
        }

        val content = JsonObject()

        bodies.forEach { body ->
            if (!content.containsKey(body.contentType)) {
                content.put(body.contentType, JsonObject().put("schema", schemaOf(body.schemaJson, ctx)))
            }
        }

        return JsonObject().put("required", true).put("content", content)
    }

    // responses

    private fun responses(
        entry: RouteEntry,
        doc: EndpointDoc?,
        parameters: JsonArray,
        hasBody: Boolean,
        loggedIn: Boolean,
        panel: Boolean,
        mutation: Boolean,
        ctx: Context
    ): JsonObject {
        val codes = sortedMapOf<Int, MutableSet<String>>()

        fun add(error: ErrorInfo) {
            if (error.status in 400..599) {
                codes.getOrPut(error.status) { sortedSetOf() }.add(error.code)
            }
        }

        if (!parameters.isEmpty || hasBody) add(BAD_REQUEST)
        if (loggedIn) {
            add(NOT_LOGGED_IN)
            add(NO_PERMISSION)
        }
        if (loggedIn && mutation) add(INVALID_CSRF_TOKEN)
        if (!panel) add(MAINTENANCE_MODE_ENABLED)
        add(RATE_LIMIT_EXCEEDED)
        add(INTERNAL_SERVER_ERROR)

        doc?.errors?.forEach { type -> errorInfo(type)?.let { add(it) } }

        val responses = JsonObject().put("200", success(entry, doc, ctx))

        codes.forEach { (status, set) ->
            responses.put(status.toString(), errorResponse(status, set))
        }

        return responses
    }

    private fun success(entry: RouteEntry, doc: EndpointDoc?, ctx: Context): JsonObject {
        val success = JsonObject().put("description", "Success")

        when {
            doc?.binary == true -> success.put(
                "content",
                JsonObject().put(
                    "application/octet-stream",
                    JsonObject().put("schema", JsonObject().put("type", "string").put("format", "binary"))
                )
            )

            else -> {
                val schema = when {
                    doc?.paginatedItem != null -> paginated(SchemaJson.of(doc.paginatedItem), ctx)
                    doc?.response != null -> schemaOf(SchemaJson.of(doc.response), ctx)
                    else -> JsonObject().put("type", "object").put("additionalProperties", true)
                }

                success.put("content", JsonObject().put("application/json", JsonObject().put("schema", schema)))
            }
        }

        val headers = JsonObject().put("Pano-Api-Level", JsonObject().put("\$ref", "#/components/headers/PanoApiLevel"))

        if (entry.deprecation != null) {
            headers
                .put("Deprecation", JsonObject().put("description", "`true`: this operation is deprecated.").put("schema", JsonObject().put("type", "string")))
                .put("Sunset", JsonObject().put("description", "When the operation goes away (HTTP date).").put("schema", JsonObject().put("type", "string")))
        }

        return success.put("headers", headers)
    }

    /** `{ items: [item], page }` for a list endpoint. */
    private fun paginated(item: JsonObject, ctx: Context): JsonObject = JsonObject()
        .put("type", "object")
        .put("required", JsonArray().add("items").add("page"))
        .put(
            "properties",
            JsonObject()
                .put("items", JsonObject().put("type", "array").put("items", schemaOf(item, ctx)))
                .put("page", JsonObject().put("\$ref", "#/components/schemas/Page"))
        )

    private fun errorResponse(status: Int, codes: Set<String>): JsonObject {
        val common = COMMON_CODES[status]

        if (common != null && common == codes) {
            return JsonObject().put("\$ref", "#/components/responses/${responseName(status)}")
        }

        return errorResponseObject(status, codes)
    }

    private fun errorResponseObject(status: Int, codes: Set<String>): JsonObject = JsonObject()
        .put("description", "${statusText(status)}: ${codes.joinToString(", ")}")
        .put("content", JsonObject().put("application/json", JsonObject().put("schema", JsonObject().put("\$ref", "#/components/schemas/Error"))))
        .put("x-pano-error-codes", JsonArray(codes.toList()))

    private fun responseName(status: Int) = "Error$status"

    private fun statusText(status: Int) = when (status) {
        400 -> "Bad request"
        401 -> "Not logged in"
        403 -> "Forbidden"
        404 -> "Not found"
        409 -> "Conflict"
        413 -> "Payload too large"
        429 -> "Too many requests"
        500 -> "Internal server error"
        503 -> "Service unavailable"
        else -> "Error $status"
    }

    // errors

    private class ErrorInfo(val code: String, val status: Int)

    private fun info(error: Error) = ErrorInfo(error.code, error.getStatusCode())

    private val BAD_REQUEST by lazy { info(BadRequest()) }
    private val NOT_LOGGED_IN by lazy { info(NotLoggedIn()) }
    private val NO_PERMISSION by lazy { info(NoPermission()) }
    private val INVALID_CSRF_TOKEN by lazy { info(InvalidCsrfToken()) }
    private val RATE_LIMIT_EXCEEDED by lazy { info(RateLimitExceeded()) }
    private val INTERNAL_SERVER_ERROR by lazy { info(InternalServerError()) }
    private val MAINTENANCE_MODE_ENABLED by lazy { info(MaintenanceModeEnabled()) }

    /** The codes every operation may answer, by status. Read from the error classes, so a frozen code is never retyped. */
    private val COMMON_CODES: Map<Int, Set<String>> by lazy {
        listOf(
            BAD_REQUEST,
            NOT_LOGGED_IN,
            NO_PERMISSION,
            INVALID_CSRF_TOKEN,
            RATE_LIMIT_EXCEEDED,
            INTERNAL_SERVER_ERROR,
            MAINTENANCE_MODE_ENABLED
        ).groupBy({ it.status }, { it.code }).mapValues { (_, codes) -> codes.toSortedSet() }
    }

    private val errorInfos = ConcurrentHashMap<KClass<*>, Optional<ErrorInfo>>()

    /** Code and status of a declared error class through its all-default constructor; `null` when it has none. */
    private fun errorInfo(type: KClass<out Error>): ErrorInfo? =
        errorInfos.computeIfAbsent(type) {
            Optional.ofNullable(ErrorStandIn.create(type.java)?.let { info(it) })
        }.orElse(null)

    // components

    private const val COOKIE_SCHEME = "sessionCookie"
    private const val BEARER_SCHEME = "bearerAuth"

    private val ERROR_SCHEMA: JsonObject = JsonObject()
        .put("type", "object")
        .put("description", "The body of every non-2xx answer.")
        .put("required", JsonArray().add("error"))
        .put(
            "properties",
            JsonObject().put(
                "error",
                JsonObject()
                    .put("type", "object")
                    .put("required", JsonArray().add("code"))
                    .put(
                        "properties",
                        JsonObject()
                            .put("code", JsonObject().put("type", "string").put("pattern", "^[A-Z][A-Z0-9_]*\$").put("description", "A stable, declared code."))
                            .put("message", JsonObject().put("type", "string").put("description", "Optional English text."))
                            .put("details", JsonObject().put("type", "object").put("additionalProperties", true).put("description", "Extra facts about the failure."))
                            .put(
                                "fields",
                                JsonObject().put("type", "object").put("additionalProperties", JsonObject().put("type", "string"))
                                    .put("description", "Field name to code, for a rejected form.")
                            )
                    )
            )
        )

    private val PAGE_SCHEMA: JsonObject = JsonObject()
        .put("type", "object")
        .put("description", "Where a list stands: page numbers start at 1, `pageSize` is at most 100.")
        .put("required", JsonArray().add("number").add("size").add("totalItems").add("totalPages"))
        .put(
            "properties",
            JsonObject()
                .put("number", JsonObject().put("type", "integer").put("minimum", 1))
                .put("size", JsonObject().put("type", "integer").put("minimum", 1))
                .put("totalItems", JsonObject().put("type", "integer").put("minimum", 0))
                .put("totalPages", JsonObject().put("type", "integer").put("minimum", 0))
        )

    private fun components(ctx: Context): JsonObject {
        val schemas = JsonObject()

        ctx.schemas.forEach { (name, schema) -> schemas.put(name, schema) }

        val responses = JsonObject()

        COMMON_CODES.toSortedMap().forEach { (status, codes) ->
            responses.put(responseName(status), errorResponseObject(status, codes))
        }

        val components = JsonObject()
            .put("schemas", schemas)
            .put("responses", responses)
            .put(
                "headers",
                JsonObject().put(
                    "PanoApiLevel",
                    JsonObject()
                        .put("description", "The API level this Pano implements.")
                        .put("schema", JsonObject().put("type", "integer"))
                )
            )

        if (ctx.securitySchemes.isNotEmpty()) {
            val schemes = JsonObject()

            ctx.securitySchemes.forEach { name -> schemes.put(name, securityScheme(name)) }

            components.put("securitySchemes", schemes)
        }

        return components
    }

    private fun securityScheme(name: String): JsonObject = when (name) {
        COOKIE_SCHEME -> JsonObject()
            .put("type", "apiKey")
            .put("in", "cookie")
            .put("name", "pano_auth_token")
            .put(
                "description",
                "The session cookie set by login. A mutation also repeats the `pano_csrf_token` cookie in the `X-CSRF-Token` header. Behind a plain-http origin the cookie names end in `_http`."
            )

        else -> JsonObject()
            .put("type", "http")
            .put("scheme", "bearer")
            .put("description", "A site session token, sent by a server-side front-end that holds a front-end key. Not valid on the panel API.")
    }

    // schemas

    private val DATA_KEYS = setOf("enum", "const", "default", "example", "examples")

    /** The schema as the document carries it: see [normalize]. */
    private fun schemaOf(schema: JsonObject, ctx: Context): JsonObject = normalize(schema, ctx) as JsonObject

    /**
     * Copies [node] with sorted keys and without any `$id`; a node whose `$id` is `pano:<Name>` is registered as
     * `components.schemas.<Name>` and replaced by a `$ref`. A second, different schema with the same name stays inline
     * (the first one wins the component), so one plugin cannot change another's shape.
     */
    private fun normalize(node: Any?, ctx: Context): Any? = when (node) {
        is JsonObject -> normalizeObject(node, ctx)
        is Map<*, *> -> normalizeObject(JsonObject(node.entries.associate { it.key.toString() to it.value }), ctx)
        is JsonArray -> JsonArray(node.list.map { normalize(it, ctx) })
        is List<*> -> JsonArray(node.map { normalize(it, ctx) })
        else -> node
    }

    private fun normalizeObject(node: JsonObject, ctx: Context): Any {
        val id = node.getValue("\$id") as? String
        val out = JsonObject()

        node.fieldNames().sorted().forEach { key ->
            if (key == "\$id") {
                return@forEach
            }

            val value = node.getValue(key)

            when {
                key in DATA_KEYS -> out.put(key, value)
                key == "required" && value is JsonArray && value.all { it is String } ->
                    out.put(key, JsonArray(value.map { it as String }.sorted()))

                else -> out.put(key, normalize(value, ctx))
            }
        }

        val name = CoreSchemas.componentName(id) ?: return out
        val existing = ctx.schemas[name]

        if (existing == null) {
            ctx.schemas[name] = out
        } else if (existing != out) {
            return out
        }

        return JsonObject().put("\$ref", "#/components/schemas/$name")
    }

    // operation ids

    /** Hands out `operationId`s: the class name minus `API`, made unique within one document. */
    private class OperationIds {
        private val taken = mutableSetOf<String>()

        fun allocate(entry: RouteEntry): String {
            val base = entry.routeClass.simpleName.removeSuffix("API").ifEmpty { "Operation" }
            val candidates = buildList {
                add(base)
                entry.pluginId?.let { add("${base}_${it.replace(Regex("[^A-Za-z0-9]+"), "_")}") }
            }

            candidates.firstOrNull { taken.add(it) }?.let { return it }

            var n = 2

            while (!taken.add("${candidates.last()}_$n")) {
                n++
            }

            return "${candidates.last()}_$n"
        }
    }
}
