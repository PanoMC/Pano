package com.panomc.platform.route

import com.panomc.platform.PluginEventManager
import com.panomc.platform.PluginManager
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginLifecycleListener
import com.panomc.platform.api.event.RouterEventListener
import com.panomc.platform.maintenance.MaintenanceGateHandler
import com.panomc.platform.model.Route
import com.panomc.platform.util.RateLimitManager
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.SessionHandler
import io.vertx.ext.web.sstore.LocalSessionStore
import io.vertx.json.schema.SchemaRepository
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class RouterProvider private constructor(
    private val vertx: Vertx,
    private val applicationContext: AnnotationConfigApplicationContext,
    private val schemaRepository: SchemaRepository,
    private val pluginManager: PluginManager,
    private val uiManager: UIManager
) : PluginLifecycleListener {
    companion object {
        fun create(
            vertx: Vertx,
            applicationContext: AnnotationConfigApplicationContext,
            schemaRepository: SchemaRepository,
            pluginManager: PluginManager,
            uiManager: UIManager
        ) =
            RouterProvider(
                vertx,
                applicationContext,
                schemaRepository,
                pluginManager,
                uiManager
            )

        private var isInitialized = false

        fun getIsInitialized() = isInitialized
    }

    private val router by lazy {
        Router.router(vertx)
    }

    private val pendingPlugins = mutableListOf<PanoPlugin>()
    private val pluginRoutes = mutableMapOf<PanoPlugin, List<io.vertx.ext.web.Route>>()

    init {
        pluginManager.addLifecycleListener(this)
    }

    fun initialize() {
        if (isInitialized) return

        // Canonical-host redirect runs before everything so cross-host browser traffic
        // is bounced to website-url before any cookie-bearing handler touches it.
        val websiteUrlRedirectHandler = applicationContext.getBean(WebsiteUrlRedirectHandler::class.java)
        router.route("/*")
            .order(-1)
            .handler(websiteUrlRedirectHandler.create())

        // Register rate limiting handler FIRST (order 0) for all API routes.
        // This ensures rate-limited requests are rejected immediately without
        // any processing overhead (no body parsing, no validation, etc.).
        val rateLimitManager = applicationContext.getBean(RateLimitManager::class.java)
        router.route("/api/*")
            .order(0)
            .handler(rateLimitManager.createHandler())

        // Maintenance gate: order 2 sits above the panel proxy (4) and the theme proxy (5) and
        // below every @Endpoint (1), so it owns all page traffic while maintenance mode is on.
        // API traffic is NOT gated here — @Endpoint handlers at order 1 terminate without calling
        // next(), so an order-2 handler never sees them; APIs are gated in Api.checkMaintenance.
        // .order() must be called before .handler() or Vert.x throws once the route is active.
        val maintenanceGateHandler = applicationContext.getBean(MaintenanceGateHandler::class.java)
        router.route("/*")
            .order(2)
            .handler(maintenanceGateHandler.create())

        val routerEventHandlers = PluginEventManager.getPanoEventListeners<RouterEventListener>()

        routerEventHandlers.forEach { eventHandler ->
            eventHandler.onRouterCreate(router)
        }

        val hostRoutes =
            applicationContext.getBeansWithAnnotation(Endpoint::class.java).values.map { it as Route }.toMutableList()

        routerEventHandlers.forEach { eventHandler ->
            eventHandler.onInitRouteList(hostRoutes)
        }

        applyRoutes(hostRoutes)

        pendingPlugins.forEach { plugin ->
            processPluginLoad(plugin)
        }
        pendingPlugins.clear()

        pluginManager.getActivePanoPlugins().forEach { plugin ->
            if (!pluginRoutes.containsKey(plugin)) {
                processPluginLoad(plugin)
            }
        }

        uiManager.prepareUI(router)

        router.route()
            .handler(SessionHandler.create(LocalSessionStore.create(vertx)))

        router.route("/panel/api/*").order(3).handler {
            it.reroute(it.request().method(), it.request().uri().replace("/panel/api/", "/api/"))
        }

        isInitialized = true
    }

    override suspend fun onPluginLoad(plugin: PanoPlugin) {
        if (!isInitialized) {
            pendingPlugins.add(plugin)
            return
        }

        processPluginLoad(plugin)
    }

    private fun processPluginLoad(plugin: PanoPlugin) {
        if (pluginRoutes.containsKey(plugin)) return

        val routes =
            plugin.pluginBeanContext.getBeansWithAnnotation(Endpoint::class.java).values.map { it as Route }.toMutableList()
        val routerEventHandlers = PluginEventManager.getPanoEventListeners<RouterEventListener>()

        routerEventHandlers.forEach { eventHandler ->
            eventHandler.onInitRouteList(routes)
        }

        pluginRoutes[plugin] = applyRoutes(routes)
    }

    override suspend fun onPluginUnload(plugin: PanoPlugin) {
        if (!isInitialized) {
            pendingPlugins.remove(plugin)
            return
        }

        pluginRoutes[plugin]?.forEach { it.disable(); it.remove() }
        pluginRoutes.remove(plugin)
    }

    private fun applyRoutes(routes: List<Route>): List<io.vertx.ext.web.Route> {
        val vertxRoutes = mutableListOf<io.vertx.ext.web.Route>()

        routes.forEach { route ->
            route.paths.forEach { path ->
                val url = path.url
                val httpMethod = path.routeType.vertxHttpMethod

                val routedRoute = if (httpMethod != null) {
                    router.route(httpMethod, url)
                } else {
                    router.route(url)
                }

                routedRoute
                    .order(route.order)

                val bodyHandler = route.bodyHandler()

                if (bodyHandler != null) {
                    routedRoute.handler(bodyHandler)
                }

                val corsHandler = route.corsHandler()

                if (corsHandler != null) {
                    routedRoute.handler(corsHandler)
                }

                val validationHandler = route.getValidationHandler(schemaRepository)

                if (validationHandler != null) {
                    routedRoute
                        .handler(validationHandler)
                }

                routedRoute
                    .handler(route.getHandler())
                    .failureHandler(route.getFailureHandler())

                vertxRoutes.add(routedRoute)
            }
        }

        return vertxRoutes
    }

    fun provide(): Router = router
}
