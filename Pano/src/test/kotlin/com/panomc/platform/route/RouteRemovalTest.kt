package com.panomc.platform.route

import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class RouteRemovalTest {
    private lateinit var vertx: Vertx
    private lateinit var router: Router

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        router = Router.router(vertx)
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    /** Routes as Pano lays them out: a gate at 0, endpoints at 1, a wildcard at 2, a catch-all without an order. */
    private fun layout(): List<io.vertx.ext.web.Route> {
        router.route("/*").order(0).handler { it.next() }
        val endpoints = (1..6).map { router.route(HttpMethod.GET, "/api/e$it").order(1).handler { context -> context.end() } }
        router.route("/*").order(2).handler { it.next() }
        router.route().handler { it.next() }

        return endpoints
    }

    @Test
    fun `Vert x's own remove leaves routes of the same order in the router`() {
        val endpoints = layout()

        endpoints.take(5).forEach { it.remove() }

        // Why RouteRemoval exists: the TreeSet comparator answers 1 for two routes of the same order.
        assertTrue(router.routes.count { route -> endpoints.any { it === route } } > 1)
    }

    @Test
    fun `removed routes are gone and the others keep their order`() {
        val endpoints = layout()
        val before: List<String?> = router.routes.map { it.path }

        RouteRemoval.remove(router, listOf(endpoints[1], endpoints[3], endpoints[4]))

        val expected = before.filter { it !in listOf("/api/e2", "/api/e4", "/api/e5") }

        assertEquals(expected, router.routes.map<io.vertx.ext.web.Route, String?> { it.path })

        // A route added afterwards still lands at its place.
        router.route(HttpMethod.GET, "/api/e7").order(1).handler { it.end() }
        assertEquals("/api/e7", router.routes[4].path)
    }
}
