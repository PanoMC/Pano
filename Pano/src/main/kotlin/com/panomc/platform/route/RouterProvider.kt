package com.panomc.platform.route

import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Route
import com.panomc.platform.model.RouteType
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.UIHelper
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.json.schema.SchemaParser
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class RouterProvider private constructor(
    vertx: Vertx,
    applicationContext: AnnotationConfigApplicationContext,
    schemaParser: SchemaParser,
    configManager: ConfigManager,
    httpClient: HttpClient,
    setupManager: SetupManager,
    pluginManager: PluginManager
) {
    companion object {
        fun create(
            vertx: Vertx,
            applicationContext: AnnotationConfigApplicationContext,
            schemaParser: SchemaParser,
            configManager: ConfigManager,
            httpClient: HttpClient,
            setupManager: SetupManager,
            pluginManager: PluginManager
        ) =
            RouterProvider(
                vertx,
                applicationContext,
                schemaParser,
                configManager,
                httpClient,
                setupManager,
                pluginManager
            )

        private var isInitialized = false

        fun getIsInitialized() = isInitialized
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
        "X-PINGARUNER",
        "x-csrf-token"
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
        val routeList = mutableListOf<Route>()

        routeList.addAll(applicationContext.getBeansWithAnnotation(Endpoint::class.java).map { it.value as Route })
        routeList.addAll(pluginManager.getPanoPlugins().map {
            it.pluginBeanContext.getBeansWithAnnotation(Endpoint::class.java)
        }.flatMap { it.values }.map { it as Route })

        router.route()
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

        UIHelper.prepareUI(setupManager, httpClient, router)

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

        router.route("/panel/api/*").order(3).handler {
            it.reroute(it.request().method(), it.request().uri().replace("/panel/api/", "/api/"))
        }

        isInitialized = true
    }

    fun provide(): Router = router
}