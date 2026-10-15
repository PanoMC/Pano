package com.panomc.platform.route

import com.panomc.platform.schema.Deprecation
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.Stability
import com.panomc.platform.schema.dsl.DescribedValidation
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * One mounted endpoint: [declared] is what the class wrote, [path] what the router serves.
 * [pluginId] is `null` for core.
 *
 * The schema layer's fields (doc 04 §5-6): [doc] is the endpoint's hand-written description, [deprecation]
 * its removal notice, [validation] what its request validation handler recorded (`null` when the endpoint
 * has none or built it with Vert.x directly). [declaredStability] is what the class wrote; [stability] is that,
 * or the derived value when it wrote none.
 */
data class RouteEntry(
    val method: String,
    val path: String,
    val declared: String,
    val pluginId: String?,
    val routeClass: Class<*>,
    val mount: Mount,
    val namespace: Namespace,
    val doc: EndpointDoc? = null,
    val declaredStability: Stability? = null,
    val deprecation: Deprecation? = null,
    val validation: DescribedValidation? = null
) {
    /** [declaredStability], else derived from where the route is mounted ([deriveStability]). */
    val stability: Stability
        get() = declaredStability ?: deriveStability(mount, namespace, pluginId, declared)

    /** Method and path with parameter names erased, so `/a/:id` and `/a/:name` collide. */
    internal val conflictKey: String
        get() = "$method ${PARAMETER.replace(path, ":")}"

    companion object {
        private val PARAMETER = Regex(":[^/]+")

        /** Core site paths (relative to `/api/v1`) that start with one of these segments are internal. */
        private val INTERNAL_PREFIXES = listOf("/setup/", "/node/", "/server/", "/maintenance/")

        /**
         * Doc 04 section 6: the panel namespace, a route mounted outside the API, and the core paths under
         * `/setup/`, `/node/`, `/server/` and `/maintenance/` are `INTERNAL`; everything else is `PUBLIC`.
         * A plugin's own `/server/...` is its own business, so the prefixes apply to core only.
         */
        fun deriveStability(mount: Mount, namespace: Namespace, pluginId: String?, declared: String): Stability =
            when {
                mount != Mount.API -> Stability.INTERNAL
                namespace == Namespace.PANEL -> Stability.INTERNAL
                pluginId == null && INTERNAL_PREFIXES.any { declared.startsWith(it) } -> Stability.INTERNAL
                else -> Stability.PUBLIC
            }
    }
}

/**
 * Every route the router has mounted, resolved. Core entries are added when the router is
 * initialised, a plugin's when it loads, and a plugin's are removed again when it unloads.
 */
@Lazy
@Component
class RouteTable {
    private val lock = Any()
    private val entries = mutableListOf<RouteEntry>()

    fun entries(): List<RouteEntry> = synchronized(lock) { entries.toList() }

    fun entriesOf(pluginId: String?): List<RouteEntry> = synchronized(lock) {
        entries.filter { it.pluginId == pluginId }
    }

    fun find(method: String, path: String): RouteEntry? = synchronized(lock) {
        entries.firstOrNull { it.method == method && it.path == path }
    }

    /**
     * Adds [batch] as a whole, or none of it: a method and path that is already taken, by the table
     * or earlier in [batch], throws [ApiPathRefusal] naming both classes; so does a [RouteEntry.doc] that
     * cannot be used ([EndpointDocRefusal]).
     */
    fun register(batch: List<RouteEntry>) {
        batch.forEach { entry ->
            val problem = entry.doc?.problem()

            if (problem != null) {
                throw EndpointDocRefusal("Endpoint ${entry.routeClass.name} doc $problem")
            }
        }

        synchronized(lock) {
            val taken = entries.associateBy { it.conflictKey }.toMutableMap()

            batch.forEach { entry ->
                val other = taken[entry.conflictKey]

                if (other != null) {
                    throw ApiPathRefusal(
                        ApiPaths.duplicateMessage(
                            entry.method,
                            entry.path,
                            other.routeClass.name,
                            entry.routeClass.name
                        )
                    )
                }

                taken[entry.conflictKey] = entry
            }

            entries.addAll(batch)
        }
    }

    /** Drops every entry of the plugin; called when it unloads. */
    fun removePlugin(pluginId: String) {
        synchronized(lock) {
            entries.removeAll { it.pluginId == pluginId }
        }
    }
}
