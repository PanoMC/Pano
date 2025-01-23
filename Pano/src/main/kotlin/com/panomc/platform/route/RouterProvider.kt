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
import io.vertx.ext.web.Router
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

    init {
        val routeList = mutableListOf<Route>()

        routeList.addAll(applicationContext.getBeansWithAnnotation(Endpoint::class.java).map { it.value as Route })
        routeList.addAll(pluginManager.getActivePanoPlugins().map {
            it.pluginBeanContext.getBeansWithAnnotation(Endpoint::class.java)
        }.flatMap { it.values }.map { it as Route })

        UIHelper.prepareUI(setupManager, httpClient, router)

        routeList.forEach { route ->
            route.paths.forEach { path ->
                val routedRoute = when (path.routeType) {
                    RouteType.ROUTE -> router.route(path.url)
                    RouteType.GET -> router.get(path.url)
                    RouteType.POST -> router.post(path.url)
                    RouteType.DELETE -> router.delete(path.url)
                    RouteType.PUT -> router.put(path.url)
                }

                routedRoute
                    .order(route.order)

                val corsHandler = route.corsHandler()

                if (corsHandler != null) {
                    routedRoute.handler(corsHandler)
                }

                val bodyHandler = route.bodyHandler()

                if (bodyHandler != null) {
                    routedRoute.handler(bodyHandler)
                }

                val validationHandler = route.getValidationHandler(schemaParser)

                if (validationHandler != null) {
                    routedRoute
                        .handler(validationHandler)
                }

                routedRoute
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