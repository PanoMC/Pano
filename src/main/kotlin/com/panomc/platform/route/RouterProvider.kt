package com.panomc.platform.route

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
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
    schemaParser: SchemaParser,
    configManager: ConfigManager
) {
    companion object {
        fun create(
            vertx: Vertx,
            applicationContext: AnnotationConfigApplicationContext,
            schemaParser: SchemaParser,
            configManager: ConfigManager
        ) =
            RouterProvider(vertx, applicationContext, schemaParser, configManager)
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
            .handler(SessionHandler.create(LocalSessionStore.create(vertx)))
            .handler(
                CorsHandler.create(".*.")
                    .allowCredentials(true)
                    .allowedHeaders(allowedHeaders)
                    .allowedMethods(allowedMethods)
            )
            .handler(
                BodyHandler.create().setDeleteUploadedFilesOnEnd(true)
                    .setUploadsDirectory(configManager.getConfig().getString("file-uploads-folder") + "/temp")
            )

        routeList.forEach { route ->
            route.paths.forEach { path ->
                val endpoint = when (path.routeType) {
                    RouteType.ROUTE -> router.route(path.url)
                    RouteType.GET -> router.get(path.url)
                    RouteType.POST -> router.post(path.url)
                    RouteType.DELETE -> router.delete(path.url)
                    RouteType.PUT -> router.put(path.url)
                }

                endpoint
                    .order(route.order)

                val validationHandler = route.getValidationHandler(schemaParser)

                if (validationHandler != null) {
                    endpoint
                        .handler(validationHandler)
                }

                endpoint
                    .handler(route.getHandler())
                    .failureHandler(route.getFailureHandler())
            }
        }
    }

    fun provide(): Router = router
}