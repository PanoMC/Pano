package com.panomc.platform.util

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Route
import com.panomc.platform.model.RouteType
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.SessionHandler
import io.vertx.ext.web.sstore.LocalSessionStore
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class RouterProvider private constructor(vertx: Vertx, applicationContext: AnnotationConfigApplicationContext) {
    companion object {
        fun create(vertx: Vertx, applicationContext: AnnotationConfigApplicationContext) =
            RouterProvider(vertx, applicationContext)
    }

    private val router by lazy {
        Router.router(vertx)
    }

    init {
        val beans = applicationContext.getBeansWithAnnotation(Endpoint::class.java)

        val routeList = beans.map { it.value as Route }

        val allowedHeaders: MutableSet<String> = HashSet()
        allowedHeaders.add("x-requested-with")
        allowedHeaders.add("Access-Control-Allow-Origin")
        allowedHeaders.add("origin")
        allowedHeaders.add("Content-Type")
        allowedHeaders.add("accept")
        allowedHeaders.add("X-PINGARUNER")

        val allowedMethods = mutableSetOf<HttpMethod>()
        allowedMethods.add(HttpMethod.GET)
        allowedMethods.add(HttpMethod.POST)
        allowedMethods.add(HttpMethod.OPTIONS)

        allowedMethods.add(HttpMethod.DELETE)
        allowedMethods.add(HttpMethod.PATCH)
        allowedMethods.add(HttpMethod.PUT)

        router.route()
            .handler(BodyHandler.create())
            .handler(SessionHandler.create(LocalSessionStore.create(vertx)))
            .handler(
                CorsHandler.create(".*.")
                    .allowCredentials(true)
                    .allowedHeaders(allowedHeaders)
                    .allowedMethods(allowedMethods)
            )

        routeList.forEach { route ->
            route.routes.forEach { url ->
                when (route.routeType) {
                    RouteType.ROUTE -> router.route(url).order(route.order).handler(route.getHandler())
                        .failureHandler(route.getFailureHandler())
                    RouteType.GET -> router.get(url).order(route.order).handler(route.getHandler())
                        .failureHandler(route.getFailureHandler())
                    RouteType.POST -> router.post(url).order(route.order).handler(route.getHandler())
                        .failureHandler(route.getFailureHandler())
                    RouteType.DELETE -> router.delete(url).order(route.order).handler(route.getHandler())
                        .failureHandler(route.getFailureHandler())
                    RouteType.PUT -> router.put(url).order(route.order).handler(route.getHandler())
                        .failureHandler(route.getFailureHandler())
                }
            }
        }
    }

    fun provide(): Router = router
}