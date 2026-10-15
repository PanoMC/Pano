package com.panomc.platform.route

import com.panomc.platform.PluginEventManager
import com.panomc.platform.PluginManager
import com.panomc.platform.StartupFailure
import com.panomc.platform.UIManager
import com.panomc.platform.access.AccessPlaneHandler
import com.panomc.platform.access.OriginPolicy
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.frontend.pages.FallbackPageRenderer
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginLifecycleListener
import com.panomc.platform.api.event.RouterEventListener
import com.panomc.platform.maintenance.MaintenanceGateHandler
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.frontend.FallbackPage
import com.panomc.platform.frontend.FallbackPageRefusal
import com.panomc.platform.frontend.FallbackPageRegistry
import com.panomc.platform.frontend.FrontendTargetsRefusal
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.plugin.PluginNamespace
import com.panomc.platform.util.FileResourceUtil.getOwnResourceStream
import com.panomc.platform.model.Api
import com.panomc.platform.model.BrowserAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Route
import com.panomc.platform.util.UsageMode
import com.panomc.platform.util.RateLimitManager
import io.vertx.core.Vertx
import com.panomc.platform.schema.dsl.DescribedValidation
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.vertx.ext.web.validation.ValidationHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
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
        private const val CORE_PLUGINS_PACKAGE = "com.panomc.platform.route.api.plugins."
        private const val FRONTEND_TARGETS_FILE = "frontend-targets.json"

        /** The owner name core routes use for their ANY_ORIGIN paths in the [OriginPolicy]. */
        private const val CORE_OWNER = "core"

        /** The URLs of the routes among [routes] (route and its resolved URL) that declare `ANY_ORIGIN`. */
        internal fun anyOriginUrls(routes: List<Pair<Route, String>>): List<String> =
            routes.filter { (route, _) -> route.browserAccess == BrowserAccess.ANY_ORIGIN }.map { it.second }.distinct()

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

    private val log: Logger = LoggerFactory.getLogger(RouterProvider::class.java)

    private val routeTable by lazy {
        applicationContext.getBean(RouteTable::class.java)
    }

    private val originPolicy by lazy {
        applicationContext.getBean(OriginPolicy::class.java)
    }

    private val frontendUrlMap by lazy {
        applicationContext.getBean(FrontendUrlMap::class.java)
    }

    private val frontendDescriptor by lazy {
        applicationContext.getBean(com.panomc.platform.frontend.FrontendDescriptor::class.java)
    }

    private val fallbackPageRegistry by lazy {
        applicationContext.getBean(FallbackPageRegistry::class.java)
    }

    /**
     * The platform's API level for the `Pano-Api-Level` header: `current` of the classpath resource
     * `pano-api-level.properties` that the build writes, 1 when the build has not produced it yet.
     */
    private val apiLevel: String by lazy {
        runCatching {
            RouterProvider::class.java.getResourceAsStream("/pano-api-level.properties")?.use { stream ->
                java.util.Properties().apply { load(stream) }.getProperty("current")?.trim()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "1"
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
        // Registered before the limiter (same order runs in registration order), so even a
        // rate-limited answer carries the header.
        // Core lives at /api/v1, the plugins at /api/plugins (decision 81): both get the same chain.
        for (root in listOf(ApiPaths.ROOT, ApiPaths.PLUGINS_ROOT)) {
            router.route("$root/*")
                .order(0)
                .handler { context ->
                    context.response().putHeader(ApiPaths.API_LEVEL_HEADER, apiLevel)
                    context.next()
                }

            // The access plane runs before the limiter: it matches the front-end key and fixes the client IP the
            // limiter (and every later handler) uses.
            val accessPlaneHandler = applicationContext.getBean(AccessPlaneHandler::class.java)
            router.route("$root/*")
                .order(0)
                .handler(accessPlaneHandler.create())

            router.route("$root/*")
                .order(0)
                .handler(rateLimitManager.createHandler())
        }

        // Maintenance gate: order 2 sits above the panel proxy (4) and the theme proxy (5) and
        // below every @Endpoint (1), so it owns all page traffic while maintenance mode is on.
        // API traffic is NOT gated here — @Endpoint handlers at order 1 terminate without calling
        // next(), so an order-2 handler never sees them; APIs are gated in Api.checkMaintenance.
        // .order() must be called before .handler() or Vert.x throws once the route is active.
        val maintenanceGateHandler = applicationContext.getBean(MaintenanceGateHandler::class.java)
        router.route("/*")
            .order(2)
            .handler(maintenanceGateHandler.create())

        // Usage-mode gate: same order (2) but registered after the maintenance gate, so Vert.x runs
        // it second — after maintenance has had its say, still above the panel proxy (4) and the
        // theme proxy (5). In SERVERS mode it bounces theme page requests to /panel. API traffic is
        // untouched for the same reason as above: @Endpoint handlers at order 1 never call next().
        val usageModeGateHandler = applicationContext.getBean(UsageModeGateHandler::class.java)
        router.route("/*")
            .order(2)
            .handler(usageModeGateHandler.create())

        val routerEventHandlers = PluginEventManager.getPanoEventListeners<RouterEventListener>()

        routerEventHandlers.forEach { eventHandler ->
            eventHandler.onRouterCreate(router)
        }

        val hostRoutes =
            applicationContext.getBeansWithAnnotation(Endpoint::class.java).values.map { it as Route }.toMutableList()

        routerEventHandlers.forEach { eventHandler ->
            eventHandler.onInitRouteList(hostRoutes)
        }

        // Core's built-in fallback pages (doc 05 section 10.3) before any plugin: a plugin's `fallback: true`
        // target is checked against the registry when its targets file is read.
        fallbackPageRegistry.registerCore(applicationContext.getBeansOfType(FallbackPage::class.java).values)

        applyRoutes(hostRoutes, null)

        // The admin's URL overrides and the front-end descriptor live in the database; the map answers from
        // memory. Until this read is done (a few milliseconds after boot) it answers without them. Before
        // setup there is no database, and that is fine.
        CoroutineScope(vertx.dispatcher()).launch {
            try {
                frontendUrlMap.reload()

                // The cached descriptor gives UIManager.activeFrontendId() its answer for every caller (CX-06).
                val databaseManager = applicationContext.getBean(com.panomc.platform.db.DatabaseManager::class.java)

                frontendDescriptor.load(databaseManager.getSqlClient())
            } catch (e: Throwable) {
                log.debug("Front-end URL overrides were not read: {}", e.message)
            }
        }

        // A plugin whose paths are refused is logged and stopped here; it must not take the boot down.
        pendingPlugins.toList().forEach { plugin ->
            processPluginLoad(plugin, propagateRefusal = false)
        }
        pendingPlugins.clear()

        pluginManager.getActivePanoPlugins().forEach { plugin ->
            if (!pluginRoutes.containsKey(plugin)) {
                processPluginLoad(plugin, propagateRefusal = false)
            }
        }

        uiManager.prepareUI(router)

        // Order 6 sits right behind the theme proxy (5), so these only ever answer while no UI owns
        // the wildcard route (boot, theme switch, or a SERVERS-mode install that runs no theme at
        // all). Two handlers at the same order run in registration order: the servers-mode
        // redirect gets first refusal and passes anything it does not claim to the 503.
        // See UIManager.uiUnavailableHandler for why 503/no-store.
        val serversModeRootHandler = applicationContext.getBean(ServersModeRootHandler::class.java)
        router.route("/*")
            .order(6)
            .handler(serversModeRootHandler.create())

        router.route("/*")
            .order(6)
            .handler(UIManager.uiUnavailableHandler())

        router.route()
            .handler(SessionHandler.create(LocalSessionStore.create(vertx)))

        isInitialized = true
    }

    override suspend fun onPluginLoad(plugin: PanoPlugin) {
        if (!isInitialized) {
            pendingPlugins.add(plugin)
            return
        }

        processPluginLoad(plugin, propagateRefusal = true)
    }

    /**
     * Mounts the plugin's endpoints. A refused path (doc 04 §2) is logged and the plugin is left
     * stopped; with [propagateRefusal] the refusal is also thrown, which fails the plugin's own
     * start (the plugin is being created right now) instead of letting it run without routes.
     */
    private fun processPluginLoad(plugin: PanoPlugin, propagateRefusal: Boolean) {
        if (pluginRoutes.containsKey(plugin)) return

        val routes =
            plugin.pluginBeanContext.getBeansWithAnnotation(Endpoint::class.java).values.map { it as Route }.toMutableList()
        val routerEventHandlers = PluginEventManager.getPanoEventListeners<RouterEventListener>()

        routerEventHandlers.forEach { eventHandler ->
            eventHandler.onInitRouteList(routes)
        }

        try {
            // Pages and targets first: a refused file mounts nothing, and a refused path drops them again.
            registerFallbackPages(plugin)
            registerFrontendTargets(plugin)

            pluginRoutes[plugin] = applyRoutes(routes, plugin.pluginId)
        } catch (refusal: RuntimeException) {
            if (refusal !is ApiPathRefusal && refusal !is FrontendTargetsRefusal && refusal !is FallbackPageRefusal) {
                throw refusal
            }

            frontendUrlMap.unregisterPlugin(plugin.pluginId)
            fallbackPageRegistry.unregisterPlugin(plugin.pluginId)

            log.error("Plugin '{}' is left stopped: {}", plugin.pluginId, refusal.message)

            runCatching { pluginManager.stopPlugin(plugin.pluginId) }

            if (propagateRefusal) {
                throw refusal
            }
        }
    }

    /** The plugin's fallback pages (doc 05 section 10.3), under its namespace. */
    private fun registerFallbackPages(plugin: PanoPlugin) {
        val pages = plugin.pluginBeanContext.getBeansOfType(FallbackPage::class.java).values

        fallbackPageRegistry.registerPlugin(plugin.pluginId, PluginNamespace.of(plugin), pages)
    }

    /** Reads `frontend-targets.json` of the plugin (doc 05 section 10.1); a plugin with no outbound links needs none. */
    private fun registerFrontendTargets(plugin: PanoPlugin) {
        val text = plugin.javaClass.classLoader.getOwnResourceStream(FRONTEND_TARGETS_FILE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

        frontendUrlMap.registerPlugin(plugin.pluginId, PluginNamespace.of(plugin), text)
    }

    override suspend fun onPluginUnload(plugin: PanoPlugin) {
        if (!isInitialized) {
            pendingPlugins.remove(plugin)
            return
        }

        pluginRoutes[plugin]?.forEach { it.disable(); it.remove() }
        pluginRoutes.remove(plugin)
        originPolicy.removeAnyOriginPaths(plugin.pluginId)
        routeTable.removePlugin(plugin.pluginId)
        frontendUrlMap.unregisterPlugin(plugin.pluginId)
        fallbackPageRegistry.unregisterPlugin(plugin.pluginId)
        runCatching { applicationContext.getBean(FallbackPageRenderer::class.java).forget(plugin.pluginId) }
    }

    /**
     * Resolves every declared path ([ApiPaths.resolve]), records the result in the [RouteTable] and
     * mounts the routes. All paths are checked before the first one is mounted, so a refusal leaves
     * nothing behind. [pluginId] is `null` for core, whose refusal is a [StartupFailure]; a plugin's
     * is an [ApiPathRefusal] for the caller to turn into a failed start.
     */
    private fun applyRoutes(routes: List<Route>, pluginId: String?): List<io.vertx.ext.web.Route> {
        val resolved = try {
            resolve(routes, pluginId)
        } catch (refusal: ApiPathRefusal) {
            if (pluginId == null) {
                throw StartupFailure(refusal.message ?: "Refused an endpoint path.", refusal)
            }

            throw refusal
        }

        val vertxRoutes = mutableListOf<io.vertx.ext.web.Route>()

        resolved.forEach { (route, path, url, validationHandler) ->
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

            // Ahead of validation, so a disabled endpoint answers 404 whatever the body says.
            if (route.usageModes != UsageMode.ALL) {
                routedRoute.handler(usageModeGate(route.usageModes))
            }

            if (validationHandler != null) {
                routedRoute
                    .handler(validationHandler)
            }

            routedRoute
                .handler(route.getHandler())
                .failureHandler(route.getFailureHandler())

            vertxRoutes.add(routedRoute)
        }

        // Routes that take third-party posts are out of the Origin gate (doc 05 §4); a plugin's are taken away again
        // when it unloads.
        originPolicy.addAnyOriginPaths(
            pluginId ?: CORE_OWNER,
            anyOriginUrls(resolved.map { it.route to it.url })
        )

        return vertxRoutes
    }

    private data class ResolvedPath(
        val route: Route,
        val path: Path,
        val url: String,
        val validationHandler: ValidationHandler?
    )

    private fun resolve(routes: List<Route>, pluginId: String?): List<ResolvedPath> {
        val resolved = mutableListOf<ResolvedPath>()
        val entries = mutableListOf<RouteEntry>()

        routes.forEach { route ->
            val routeClass = route.javaClass

            route.paths.forEach { path ->
                if (route.mount == Mount.API) {
                    val refusal = ApiPaths.refusal(
                        path.url,
                        routeClass.name,
                        pluginId,
                        inPluginsPackage = routeClass.name.startsWith(CORE_PLUGINS_PACKAGE)
                    )

                    if (refusal != null) {
                        throw ApiPathRefusal(refusal)
                    }
                }

                val url = ApiPaths.resolve(path.url, route.mount, route.namespace, pluginId)

                val validationHandler = route.getValidationHandler(schemaRepository)
                val api = route as? Api

                resolved.add(ResolvedPath(route, path, url, validationHandler))

                api?.deprecation?.let { deprecation ->
                    log.warn(
                        "Endpoint {} {} is deprecated since API level {} and goes away after {}{}",
                        path.routeType.vertxHttpMethod?.name() ?: "ALL",
                        url,
                        deprecation.sinceLevel,
                        deprecation.removeAfter,
                        deprecation.replacement?.let { "; use $it" } ?: ""
                    )
                }

                entries.add(
                    RouteEntry(
                        method = path.routeType.vertxHttpMethod?.name() ?: "ALL",
                        path = url,
                        declared = path.url,
                        pluginId = pluginId,
                        routeClass = routeClass,
                        mount = route.mount,
                        namespace = route.namespace,
                        doc = api?.doc,
                        declaredStability = api?.stability,
                        deprecation = api?.deprecation,
                        validation = validationHandler as? DescribedValidation
                    )
                )
            }
        }

        routeTable.register(entries)

        return resolved
    }

    /**
     * 404 for a route that does not exist in the current usage mode ([Route.usageModes]), in the
     * JSON shape every API error has. The mode is read per request.
     */
    private fun usageModeGate(modes: Set<UsageMode>): io.vertx.core.Handler<io.vertx.ext.web.RoutingContext> {
        val configManager = applicationContext.getBean(ConfigManager::class.java)

        return io.vertx.core.Handler { context ->
            if (configManager.config.effectiveUsageMode in modes) {
                context.next()

                return@Handler
            }

            val response = context.response()

            if (response.ended() || response.headWritten()) {
                return@Handler
            }

            val notExists = NotExists()

            response
                .setStatusCode(notExists.getStatusCode())
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(notExists.encode())
        }
    }

    fun provide(): Router = router
}
