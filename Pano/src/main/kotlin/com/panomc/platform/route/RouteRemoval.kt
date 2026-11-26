package com.panomc.platform.route

import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import org.slf4j.LoggerFactory

/**
 * Takes routes out of a Vert.x router for good.
 *
 * `Route.remove()` does not work for most of Pano's routes: Vert.x (5.2.1) keeps the routes in a `TreeSet` whose
 * comparator answers 1 for two different routes of the same order (`RouterState.routeComparator`), so
 * `TreeSet.remove` walks right past the route and leaves it in place. Pano gives most routes the same order (1 for every
 * endpoint), so a removed plugin route stayed in the router, disabled, and pinned the plugin's class loader through its
 * handler (seen in a heap dump after market updates, 2026-11-23).
 *
 * Here the router's route set is rebuilt without the routes, compared by identity, the way `RouterImpl.remove` swaps in
 * a new state: the routes left keep their order, ties included (the rebuilt set takes them in their current order).
 * If Vert.x's internals ever change so this does not work, it falls back to `Route.remove()` and says so.
 */
object RouteRemoval {
    private val log = LoggerFactory.getLogger(RouteRemoval::class.java)

    fun remove(router: Router, routes: Collection<Route>) {
        if (routes.isEmpty()) {
            return
        }

        routes.forEach { runCatching { it.disable() } }

        if (!removeFromState(router, routes)) {
            routes.forEach { route -> runCatching { route.remove() } }
        }

        val left = router.routes.count { current -> routes.any { it === current } }

        if (left > 0) {
            log.warn("{} route(s) could not be removed from the router; they stay disabled", left)
        }
    }

    private fun removeFromState(router: Router, routes: Collection<Route>): Boolean = try {
        // RouterImpl's own synchronized methods lock the router: route changes stay serialized with them.
        val target = unwrap(router)

        synchronized(target) {
            val stateField = findField(target.javaClass, "state")
            val state = stateField.get(target)

            val current = method(state.javaClass, "getRoutes").invoke(state) as Set<*>
            val kept = LinkedHashSet<Any?>(current.filter { existing -> routes.none { it === existing } })

            val newState = method(state.javaClass, "setRoutes", Set::class.java).invoke(state, kept)

            stateField.set(target, newState)

            // As RouterImpl.remove does.
            @Suppress("UNCHECKED_CAST")
            (method(newState.javaClass, "getModifiedHandler").invoke(newState) as? io.vertx.core.Handler<Router>)
                ?.handle(target)
        }

        true
    } catch (e: Exception) {
        log.warn("Could not rebuild the router's routes ({}); removing them one by one", e.toString())

        false
    }

    /**
     * The real router behind a Spring proxy. Classes that take the router `@Lazy` get a proxy, which has no `state`
     * field: without this the rebuild always failed and fell back to `Route.remove()` (the warning on every theme switch).
     */
    private fun unwrap(router: Router): Router {
        val target = (router as? org.springframework.aop.framework.Advised)?.targetSource?.target

        return target as? Router ?: router
    }

    private fun method(type: Class<*>, name: String, vararg parameters: Class<*>) =
        type.getDeclaredMethod(name, *parameters).also { it.isAccessible = true }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field {
        var current: Class<*>? = type

        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true

                return it
            }

            current = current.superclass
        }

        throw NoSuchFieldException(name)
    }
}
