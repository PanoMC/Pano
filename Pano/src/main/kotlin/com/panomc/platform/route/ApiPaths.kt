package com.panomc.platform.route

/** Where a route is mounted: under the versioned API prefix, or verbatim (templates, host-sso). */
enum class Mount { API, ROOT }

/** Which API namespace a route belongs to: the site API, or the internal panel API. */
enum class Namespace { SITE, PANEL }

/** A declared endpoint path Pano refuses to register. [message] names the class and the fix. */
open class ApiPathRefusal(message: String) : RuntimeException(message)

/** An endpoint's [com.panomc.platform.schema.EndpointDoc] that cannot be used; handled like a refused path. */
class EndpointDocRefusal(message: String) : ApiPathRefusal(message)

/**
 * How a declared endpoint path becomes a mounted path (doc 04 §2). A declared path is relative to
 * the namespace of its class and never contains `/api` or `/panel`:
 *
 * | Class | Declared | Mounted |
 * |---|---|---|
 * | core site API | `/posts` | `/api/v1/posts` |
 * | core panel API | `/settings` | `/api/v1/panel/settings` |
 * | plugin site API | `/store/products` | `/api/plugins/<pluginId>/store/products` |
 * | plugin panel API | `/products` | `/api/plugins/<pluginId>/panel/products` |
 *
 * Plugin APIs are unversioned (decision 81): the `v1` is the core's version, not the plugin's. Core's own
 * endpoints about plugins (translations, OpenAPI document, UI files: `/api/v1/plugins/<pluginId>/_/...`, and the
 * panel's plugin management under `/api/v1/panel/plugins/...`) stay under [ROOT]; they cannot meet a plugin route,
 * because a plugin lives below [PLUGINS_ROOT], which is not below [ROOT].
 */
object ApiPaths {
    /** The whole API namespace. Only [ROOT] is mounted; the rest of `/api` answers like any unknown page. */
    const val BASE = "/api"

    const val ROOT = "$BASE/v1"

    /** The unversioned namespace of every plugin API: `/api/plugins/<pluginId>/...`. */
    const val PLUGINS_ROOT = "$BASE/plugins"

    /** The internal panel namespace under [ROOT]. */
    const val PANEL_ROOT = "$ROOT/panel"
    const val RESERVED_SEGMENT = "_"

    /** Response header every API answer carries (the value is the platform's current API level). */
    const val API_LEVEL_HEADER = "Pano-Api-Level"

    fun core(path: String) = ROOT + path

    fun panel(path: String) = "$PANEL_ROOT$path"

    fun plugin(pluginId: String, path: String) = "$PLUGINS_ROOT/$pluginId$path"

    fun pluginPanel(pluginId: String, path: String) = "$PLUGINS_ROOT/$pluginId/panel$path"

    /**
     * Whether [path] sits in the `/api` namespace (the versioned API or a stale pre-cutover path). Gates that
     * keep API traffic out of page logic ask this, so an old `/api/posts` is never taken for a theme page.
     */
    fun isApi(path: String) = path == BASE || path.startsWith("$BASE/")

    /** Whether [path] is [ROOT] or below it, the paths the router mounts. */
    fun isMounted(path: String) =
        path == ROOT || path.startsWith("$ROOT/") || path == PLUGINS_ROOT || path.startsWith("$PLUGINS_ROOT/")

    /** Whether [path] is a plugin's panel endpoint (`/api/plugins/<pluginId>/panel/...`). */
    fun isPluginPanel(path: String): Boolean {
        if (!path.startsWith("$PLUGINS_ROOT/")) return false

        val rest = path.removePrefix("$PLUGINS_ROOT/")
        val id = rest.substringBefore('/')

        return id.isNotEmpty() && rest.removePrefix(id).let { it == "/panel" || it.startsWith("/panel/") }
    }

    /** Whether [path] is a panel endpoint of core or of a plugin: the panel rate tier, the panel's treatment. */
    fun isPanelApi(path: String) = path.startsWith("$PANEL_ROOT/") || isPluginPanel(path)

    /**
     * `ROOT` mounts are returned verbatim; otherwise the matching function of the four above for
     * ([namespace], `pluginId == null`).
     */
    fun resolve(declared: String, mount: Mount, namespace: Namespace, pluginId: String?): String {
        if (mount == Mount.ROOT) {
            return declared
        }

        return when {
            namespace == Namespace.SITE && pluginId == null -> core(declared)
            namespace == Namespace.PANEL && pluginId == null -> panel(declared)
            namespace == Namespace.SITE -> plugin(pluginId!!, declared)
            else -> pluginPanel(pluginId!!, declared)
        }
    }

    /**
     * Why a path declared by [routeClass] is refused, or `null` when it is fine. Only `API` mounts
     * are checked; a `ROOT` mount declares the full path itself.
     *
     * [pluginId] is `null` for core. [inPluginsPackage] says the class lives in
     * `com.panomc.platform.route.api.plugins`, the one core package allowed to declare `/plugins/...`.
     */
    fun refusal(declared: String, routeClass: String, pluginId: String?, inPluginsPackage: Boolean = false): String? {
        val head = "Endpoint $routeClass declares the path \"$declared\""

        if (declared.isEmpty() || !declared.startsWith("/")) {
            val suggestion = if (declared.isEmpty()) "/hello" else "/$declared"

            return "$head, which does not start with \"/\"; declare \"$suggestion\""
        }

        if (declared.isSegmentPrefix("/api")) {
            val rest = declared.removePrefix("/api").ifEmpty { "/" }

            val adds = if (pluginId == null) ROOT else "$PLUGINS_ROOT/$pluginId"

            return "$head; declare \"$rest\", not \"$declared\"; Pano adds $adds itself"
        }

        if (declared.isSegmentPrefix("/panel")) {
            val rest = declared.removePrefix("/panel").ifEmpty { "/" }

            return "$head; extend PanelApi and declare \"$rest\"; Pano adds /panel itself"
        }

        val first = declared.removePrefix("/").substringBefore('/')

        if (pluginId != null) {
            if (first == RESERVED_SEGMENT || first.startsWith(":")) {
                return "$head; put a resource name first: \"/items/:id\""
            }

            return null
        }

        if (first == "plugins" && !inPluginsPackage) {
            return "$head; the first segment \"plugins\" is reserved for plugins"
        }

        return null
    }

    /** Message for two endpoints that resolve to the same method and path. */
    fun duplicateMessage(method: String, path: String, firstClass: String, secondClass: String) =
        "Endpoints $firstClass and $secondClass both declare $method $path; give one of them another path"

    private fun String.isSegmentPrefix(prefix: String) = this == prefix || startsWith("$prefix/")
}
