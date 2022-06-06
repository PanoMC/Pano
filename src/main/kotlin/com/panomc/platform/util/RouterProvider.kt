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
import io.vertx.json.schema.SchemaParser
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class RouterProvider private constructor(
    vertx: Vertx,
    applicationContext: AnnotationConfigApplicationContext,
    schemaParser: SchemaParser
) {
    companion object {
        fun create(vertx: Vertx, applicationContext: AnnotationConfigApplicationContext, schemaParser: SchemaParser) =
            RouterProvider(vertx, applicationContext, schemaParser)
    }

    private val router by lazy {
        Router.router(vertx)
    }

    private val allowedHeaders = setOf(
        "x-requested-with",
        "Access-Control-Allow-Origin",
        "origin",
        "Content-Type",
        "accept",
        "X-PINGARUNER"
    )

    private val allowedMethods = setOf<HttpMethod>(
        HttpMethod.GET,
        HttpMethod.POST,
        HttpMethod.OPTIONS,
        HttpMethod.DELETE,
        HttpMethod.PATCH,
        HttpMethod.PUT
    )

    init {
        val beans = applicationContext.getBeansWithAnnotation(Endpoint::class.java)

        val routeList = beans.map { it.value as Route }

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
                    RouteType.ROUTE -> router.route(url)
                    RouteType.GET -> router.get(url)
                    RouteType.POST -> router.post(url)
                    RouteType.DELETE -> router.delete(url)
                    RouteType.PUT -> router.put(url)
                }
                    .order(route.order)
                    .handler(route.getValidationHandler(schemaParser))
                    .handler(route.getHandler())
                    .failureHandler(route.getFailureHandler())
            }
        }
    }

    fun provide(): Router = router
}