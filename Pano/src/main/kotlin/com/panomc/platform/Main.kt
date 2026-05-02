package com.panomc.platform

import com.panomc.platform.annotation.Boot
import com.panomc.platform.api.PluginDatabaseManager
import com.panomc.platform.auth.PermissionRegistry
import com.panomc.platform.command.CommandExecutor
import com.panomc.platform.command.CommandManager
import com.panomc.platform.command.CommandSender
import com.panomc.platform.command.ConsoleInputReader
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.MariaDBManager
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.route.RouterProvider
import com.panomc.platform.server.ServerManager
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.ssl.AcmeManager
import com.panomc.platform.util.*
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.VertxException
import io.vertx.core.VertxOptions
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.io.File
import java.util.jar.Manifest
import kotlin.system.exitProcess

@Boot
class Main : CoroutineVerticle() {
    companion object {
        private val options by lazy {
            VertxOptions()
        }

        private val vertx by lazy {
            Vertx.vertx(options)
        }

        private val urlClassLoader = ClassLoader.getSystemClassLoader()

        private val manifest by lazy {
            val manifestUrl = urlClassLoader.getResourceAsStream("META-INF/MANIFEST.MF")

            Manifest(manifestUrl)
        }

        private val mode by lazy {
            try {
                manifest.mainAttributes.getValue("MODE").toString()
            } catch (e: Exception) {
                "RELEASE"
            }
        }

        val ENVIRONMENT =
            if (mode != "DEVELOPMENT" && System.getenv("EnvironmentType").isNullOrEmpty()) EnvironmentType.RELEASE
            else EnvironmentType.DEVELOPMENT

        val VERSION by lazy {
            try {
                manifest.mainAttributes.getValue("VERSION").toString()
            } catch (e: Exception) {
                System.getenv("PanoVersion").toString()
            }
        }

        val STAGE by lazy {
            ReleaseStage.valueOf(
                stage = try {
                    manifest.mainAttributes.getValue("BUILD_TYPE").toString()
                } catch (e: Exception) {
                    System.getenv("PanoBuildType").toString()
                }
            )!!
        }

        val OPERATING_SYSTEM by lazy {
            val osName = System.getProperty("os.name").lowercase()
            when {
                osName.contains("win") || osName.contains("windows") -> OperatingSystem.WINDOWS
                osName.contains("mac") || osName.contains("darwin") || osName.contains("osx") -> OperatingSystem.DARWIN
                else -> OperatingSystem.LINUX
            }
        }

        val ARCHITECTURE by lazy {
            val osArch = System.getProperty("os.arch").lowercase()
            when {
                osArch.contains("amd64") || osArch.contains("x86_64") -> Architecture.X64
                osArch.contains("aarch64") -> Architecture.AARCH64
                else -> Architecture.X64 // Default fallback
            }
        }

        val START_TIME = System.currentTimeMillis()

        @Volatile
        var IS_GUI = false
            private set

        var IS_DEV = false
            private set

        var IS_DEMO = false
            private set

        var STARTUP_ARGS: Array<String> = emptyArray()
            private set

        @JvmStatic
        fun main(args: Array<String>) {
            // Silence JLine warnings as early as possible
            System.setProperty("org.jline.utils.Log.level", "ERROR")

            STARTUP_ARGS = args
            IS_DEV = Args.hasFlag(args, "--dev")
            IS_DEMO = Args.hasFlag(args, "--demo")
            val noGui = Args.hasFlag(args, "-nogui")

            if (!noGui) {
                // Try GUI first; if it fails (headless or no display), do normal start.
                if (UiConsole.isGuiAvailable()) {
                    UiConsole.showConsoleWindow("Pano v${VERSION} Console")
                    IS_GUI = true
                    vertx.deployVerticle(Main())
                    return
                }
                // GUI not available -> fall back to normal start
            }

            runBlocking {
                vertx.deployVerticle(Main()).coAwait()
                
                // Wait for the shutdown signal from anywhere (stop command, ctrl+c, gui close)
                mainShutdownDeferred.await()
                
                // Final cleanup: stop terminal reader loop
                ConsoleInputReader.stop(false)
                
                logger.info("Pano is now stopped. Bye!")
                
                // Final flush
                System.out.flush()
                System.err.flush()
                
                // Give a tiny bit of time for logs to process
                delay(100)
                
                // Truly stop terminal and JVM
                ConsoleInputReader.stop(true)
                exitProcess(0)
            }
        }

        enum class EnvironmentType {
            DEVELOPMENT, RELEASE
        }

        lateinit var applicationContext: AnnotationConfigApplicationContext

        val commandManager = CommandManager()

        private val mainShutdownDeferred = CompletableDeferred<Unit>()

        fun signalMainShutdown() = mainShutdownDeferred.complete(Unit)

        private val isStopping = java.util.concurrent.atomic.AtomicBoolean(false)
        fun isStopping() = isStopping.get()

        val logger by lazy {
            LoggerFactory.getLogger("Pano")
        }

        /** Bold green + reset so the Pano URL stands out in the console. */
        private const val CONSOLE_BOLD_GREEN = "\u001B[1;32m"
        private const val CONSOLE_RESET = "\u001B[0m"
    }

    private lateinit var router: Router
    private lateinit var configManager: ConfigManager
    private lateinit var pluginManager: PluginManager
    private lateinit var uiManager: UIManager
    private lateinit var acmeManager: AcmeManager
    private lateinit var databaseManager: DatabaseManager
    private val shutdownDeferred = CompletableDeferred<Unit>()

    suspend fun shutdown(error: Boolean = false, exit: Boolean = true) {
        if (!isStopping.compareAndSet(false, true)) {
            // Wait for existing shutdown if requested
            try {
                withTimeout(10000) { shutdownDeferred.await() }
            } catch (_: Exception) {}
            return
        }

        logger.info("Gracefully shutting down Pano...")

        try {
            withContext(Dispatchers.IO) {
                withTimeout(10000) {
                    Main.vertx.close().coAwait()
                }
            }
        } catch (e: Exception) {
            logger.warn("Vert.x shutdown reached timeout or failed: ${e.message}")
        } finally {
            shutdownDeferred.complete(Unit)
            
            // This wakes up the main thread's await()
            signalMainShutdown()
            
            // If we are NOT in the main loop thread (e.g. GUI mode), 
            // we should manually exit after a delay if signal wasn't caught
            if (IS_GUI && exit) {
                delay(1000)
                exitProcess(if (error) 1 else 0)
            }
        }
    }

    private fun hookCommands() {
        val commandExecutors = applicationContext.getBeansOfType(CommandExecutor::class.java)
        commandExecutors.values.forEach { executor ->
            commandManager.registerCommands(executor)
        }

        UiConsole.setHistoryLimit(configManager.config.consoleHistoryLimit)
        
        UiConsole.setCommandHandler { cmd ->
            commandManager.executeCommand(UiConsoleCommandSender(), cmd)
        }

        ConsoleInputReader(this, commandManager, configManager).start()
    }

    private class UiConsoleCommandSender : CommandSender {
        override fun sendMessage(message: String) {
            Main.logger.info(message)
        }

        override fun getName(): String {
            return "UI_CONSOLE"
        }
    }


    private fun hookShutdown() {
        UiConsole.setInterruptHandler { shutdown() }

        Runtime.getRuntime().addShutdownHook(Thread {
            if (!isStopping()) {
                runBlocking { shutdown(exit = false) }
            }
        })
    }

    override suspend fun start() {
        logger.info(
            "\n" + " ______   ______     __   __     ______    \n" + "/\\  == \\ /\\  __ \\   /\\ \"-.\\ \\   /\\  __ \\   \n" + "\\ \\  _-/ \\ \\  __ \\  \\ \\ \\-.  \\  \\ \\ \\/\\ \\  \n" + " \\ \\_\\    \\ \\_\\ \\_\\  \\ \\_\\\\\"\\_\\  \\ \\_____\\ \n" + "  \\/_/     \\/_/\\/_/   \\/_/ \\/_/   \\/_____/  v${VERSION}\n" + "                                           "
        )
        logger.info("Hello World!")

        init()

        startWebServer()
    }

    override suspend fun stop() {
        logger.info("Stopping Pano components...")

        if (::pluginManager.isInitialized) {
            logger.info("Stopping plugins...")
            try {
                pluginManager.stopPlugins()
            } catch (e: Exception) {
                logger.error("Failed to stop plugins", e)
            }
        }

        if (::uiManager.isInitialized) {
            uiManager.shutdown()
        }

        try {
            if (applicationContext.containsBean("mariaDBManager")) {
                val mariaDBManager = applicationContext.getBean(MariaDBManager::class.java)
                mariaDBManager.stop()
            }
        } catch (_: Exception) {}

        ConsoleInputReader.stop(false)
        UiConsole.markStopped()
        logger.info("Pano is now fully stopped. Bye!")
    }

    private suspend fun executeBlocking(unit: () -> Unit) {
        vertx.executeBlocking {
            unit.invoke()
        }.onFailure {
            it.printStackTrace()
        }.coAwait()
    }

    private suspend fun init() {
        hookShutdown()

        executeBlocking {
            initDependencyInjection()
        }

        initConfigManager()

        executeBlocking {
            clearTempFiles()
        }

        var isPlatformInstalled = false

        executeBlocking {
            isPlatformInstalled = initSetupManager()
        }

        // Init MariaDB before plugins
        initMariaDBManager()

        executeBlocking {
            initPluginManager()

            initPermissionRegistry()

            initPlugins()
        }

        if (isPlatformInstalled) {
            initDatabaseManager()

            initI18nManager()

            initServerManager()

            initUpdateManager()

            initOnlinePlayerTracker()
        }

        executeBlocking {
            initUiManager()

            initRoutes()

            runBlocking {
                initAcmeManager()
            }
        }

        hookCommands()
    }

    private suspend fun initAcmeManager() {
        logger.info("Initializing ACME manager")

        acmeManager = applicationContext.getBean(AcmeManager::class.java)

        acmeManager.init(router)
    }

    private suspend fun initI18nManager() {
        logger.info("Initializing i18n manager")

        val i18nManager = applicationContext.getBean(I18nManager::class.java)

        i18nManager.init()
    }

    private suspend fun initUpdateManager() {
        logger.info("Initializing update manager")

        val updateManager = applicationContext.getBean(UpdateManager::class.java)

        updateManager.init()
    }

    private fun initOnlinePlayerTracker() {
        logger.info("Initializing online player tracker")

        val onlinePlayerTracker = applicationContext.getBean(OnlinePlayerTracker::class.java)

        onlinePlayerTracker.start()
    }

    private fun initPluginManager() {
        logger.info("Initializing plugin manager")

        pluginManager = applicationContext.getBean(PluginManager::class.java)
    }

    private fun initPlugins() {
        logger.info("Loading plugins")

        pluginManager.loadPlugins()

        logger.info("Starting enabled plugins")

        pluginManager.startPlugins()
    }

    private fun initPermissionRegistry() {
        logger.info("Initializing permission registry")

        val permissionRegistry = applicationContext.getBean(PermissionRegistry::class.java)

        permissionRegistry.initialize(applicationContext)

        pluginManager.addLifecycleListener(permissionRegistry)
    }

    internal fun clearTempFiles() {
        val uploadsFolderTempFolder = File(configManager.config.fileUploadsFolder + File.separator + "temp")

        uploadsFolderTempFolder.deleteRecursively()

        File(AppConstants.TEMP_FOLDER).deleteRecursively()
    }

    private fun initDependencyInjection() {
        logger.info("Initializing dependency injection")

        SpringConfig.setDefaults(vertx, logger)

        applicationContext = AnnotationConfigApplicationContext()
        applicationContext.register(SpringConfig::class.java)
        applicationContext.beanFactory.registerSingleton("main", this)
        applicationContext.beanFactory.registerSingleton("commandManager", commandManager)
        applicationContext.refresh()
    }

    private fun initUiManager() {
        logger.info("Initializing UI manager")

        uiManager = applicationContext.getBean(UIManager::class.java)

        uiManager.init()
    }

    private suspend fun initConfigManager() {
        logger.info("Initializing config manager")

        configManager = applicationContext.getBean(ConfigManager::class.java)

        try {
            configManager.init()
        } catch (e: Exception) {
            logger.error(e.message ?: e.toString(), e)
        }
    }

    private fun initSetupManager(): Boolean {
        logger.info("Checking is platform installed")

        val setupManager = applicationContext.getBean(SetupManager::class.java)

        if (!setupManager.isSetupDone()) {
            logger.info("Platform is not installed! Skipping database manager initializing")

            return false
        }

        logger.info("Platform is installed")

        return true
    }

    private suspend fun initMariaDBManager() {
        val setupManager = applicationContext.getBean(SetupManager::class.java)
        if (configManager.config.database.type == "portable" && setupManager.isSetupDone()) {
            logger.info("Starting Portable MariaDB...")
            val mariaDBManager = applicationContext.getBean(MariaDBManager::class.java)
            mariaDBManager.start()
        }
    }

    private suspend fun initDatabaseManager() {
        logger.info("Initializing database manager")
        databaseManager = applicationContext.getBean(DatabaseManager::class.java)
        databaseManager.init()

        val pluginDatabaseManager = applicationContext.getBean(PluginDatabaseManager::class.java)

        pluginDatabaseManager.checkOrphanedPlugins()
    }

    private suspend fun initServerManager() {
        logger.info("Initializing server manager")

        val serverManager = applicationContext.getBean(ServerManager::class.java)

        serverManager.init()
    }

    private fun initRoutes() {
        logger.info("Initializing routes")

        try {
            val routerProvider = applicationContext.getBean(RouterProvider::class.java)
            routerProvider.initialize()
            router = applicationContext.getBean(Router::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startWebServer() {
        val serverConfig = configManager.config.server

        // Vert.x may throw on junk HTTP: bad % escapes in path, HTTP/1.x without Host (RFC 9112), etc.
        val safeRouter = catchBadClientRequests(router)

        if (serverConfig.sslMode == PanoConfig.Companion.SslMode.DISABLED) {
            startHttpServer(serverConfig, safeRouter)
        } else {
            val httpHandler =
                if (serverConfig.redirectHttps) {
                    catchBadClientRequests(getHttpRedirectHandler(serverConfig, safeRouter))
                } else {
                    safeRouter
                }
            startHttpServer(serverConfig, httpHandler)
            startHttpsServer(serverConfig, safeRouter)
        }
    }

    /**
     * Turns common client/protocol garbage into HTTP 400 instead of "Unhandled exception in router".
     * Does not cover pre-handler codec errors (e.g. TLS or HTML sent to a plain HTTP port — see ConnectionBase logs).
     */
    private fun catchBadClientRequests(handler: Handler<HttpServerRequest>): Handler<HttpServerRequest> {
        return Handler { req ->
            try {
                handler.handle(req)
            } catch (e: IllegalArgumentException) {
                if (e.message?.contains("Invalid escape sequence") == true) {
                    respond400IfOpen(req)
                } else {
                    throw e
                }
            } catch (e: VertxException) {
                if (e.message?.contains("Host") == true && e.message?.contains("required") == true) {
                    respond400IfOpen(req)
                } else {
                    throw e
                }
            }
        }
    }

    private fun respond400IfOpen(req: HttpServerRequest) {
        if (!req.response().ended()) {
            req.response().setStatusCode(400).end()
        }
    }

    private fun buildPanoUrlFromWebsiteUrl(): String? {
        val raw = configManager.config.websiteUrl.trim()
        if (raw.isEmpty()) return null
        var base = raw.trimEnd('/')
        if (base.endsWith("/panel", ignoreCase = true)) {
            base = base.substring(0, base.length - 6).trimEnd('/')
        }
        return base.ifEmpty { null }
    }

    private fun buildLocalPanoUrl(scheme: String, host: String, port: Int): String {
        val h = when (host) {
            "0.0.0.0" -> "127.0.0.1"
            "[::]", "::" -> "127.0.0.1"
            else -> host
        }
        val defaultPort = if (scheme == "https") 443 else 80
        val p = if (port == defaultPort) "" else ":$port"
        return "$scheme://$h$p"
    }

    private fun logWebServerReady(scheme: String, host: String, port: Int) {
        val startup = TimeUtil.getStartupTime()
        logger.info("Started listening on $scheme://$host:$port, ready to rock & roll! (${startup}s)")
        val green = CONSOLE_BOLD_GREEN
        val reset = CONSOLE_RESET
        val fromConfig = buildPanoUrlFromWebsiteUrl()
        if (fromConfig != null) {
            logger.info("${green}You can visit your Pano at: $fromConfig$reset")
        } else {
            val fallback = buildLocalPanoUrl(scheme, host, port)
            logger.info("${green}You can visit your Pano at: $fallback (set website-url in config to your public site URL if users reach Pano through a different host or port).$reset")
        }
        UiConsole.markReady()
    }

    private fun startHttpServer(serverConfig: PanoConfig.Companion.ServerConfig, handler: Handler<HttpServerRequest>) {
        val host = serverConfig.host
        val port = serverConfig.httpPort
        val isSslEnabled = serverConfig.sslMode != PanoConfig.Companion.SslMode.DISABLED

        logger.info("Creating HTTP server on port $port")
        vertx.createHttpServer().requestHandler(handler).listen(port, host).onSuccess {
                if (isSslEnabled) {
                    logger.info("HTTP server is listening on $port. Ready for ACME challenges if needed.")
                    if (serverConfig.sslMode == PanoConfig.Companion.SslMode.LETS_ENCRYPT) {
                        acmeManager.prepareCertificates()
                    }
                } else {
                    logWebServerReady("http", host, port)
                }
            }.onFailure { result ->
                val message = "Failed to listen on http://$host:$port, reason: ${result.message ?: result.toString()}"
                if (isSslEnabled) {
                    logger.warn(message)
                    UiConsole.markReady()
                } else {
                    logger.error(message)
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        runBlocking { shutdown(true) }
                    }
                }
            }
    }

    private fun startHttpsServer(serverConfig: PanoConfig.Companion.ServerConfig, handler: Handler<HttpServerRequest>) {
        val host = serverConfig.host
        val port = serverConfig.httpsPort
        val (options, canStart) = prepareHttpsOptions(serverConfig)

        if (!canStart) {
            UiConsole.markReady()
            return
        }

        logger.info("Creating HTTPS server on port $port (Mode: ${serverConfig.sslMode})")
        vertx.createHttpServer(options).requestHandler(handler).listen(port, host).onSuccess {
                logWebServerReady("https", host, port)
            }.onFailure { result ->
                logger.error("Failed to listen on https://$host:$port, reason: ${result.message ?: result.toString()}")
                UiConsole.markReady()
            }
    }

    private fun getHttpRedirectHandler(
        serverConfig: PanoConfig.Companion.ServerConfig,
        delegate: Handler<HttpServerRequest>
    ): Handler<HttpServerRequest> {
        return Handler { req ->
            if (req.path().startsWith("/.well-known/acme-challenge/")) {
                delegate.handle(req)
            } else {
                val hostHeader = req.getHeader("Host")
                val domain = if (hostHeader != null) {
                    if (hostHeader.startsWith("[")) hostHeader.substringBefore("]:") + "]" else hostHeader.substringBefore(
                        ":"
                    )
                } else {
                    try {
                        java.net.URI(configManager.config.websiteUrl).host
                    } catch (e: Exception) {
                        null
                    }
                }

                if (domain == null || domain == "0.0.0.0") {
                    delegate.handle(req)
                } else {
                    val port = serverConfig.httpsPort
                    val portSuffix = if (port == 443) "" else ":$port"
                    val query = if (req.query().isNullOrBlank()) "" else "?" + req.query()
                    val redirectTo = "https://$domain$portSuffix${req.path()}$query"

                    req.response().setStatusCode(301).putHeader("Location", redirectTo).end()
                }
            }
        }
    }

    private fun prepareHttpsOptions(serverConfig: PanoConfig.Companion.ServerConfig): Pair<HttpServerOptions, Boolean> {
        val options = HttpServerOptions()
        var canStart = false

        when (serverConfig.sslMode) {
            PanoConfig.Companion.SslMode.MANUAL -> {
                val cert = serverConfig.sslCert
                val key = serverConfig.sslKey
                if (!cert.isNullOrBlank() && !key.isNullOrBlank()) {
                    try {
                        options.setSsl(true).setKeyCertOptions(
                                PemKeyCertOptions().setCertValue(Buffer.buffer(cert)).setKeyValue(Buffer.buffer(key))
                            )
                        canStart = true
                    } catch (e: Exception) {
                        logger.error("Failed to load SSL certificates: ${e.message}")
                    }
                } else {
                    logger.error("SSL Mode is MANUAL but certificates are missing!")
                }
            }

            PanoConfig.Companion.SslMode.LETS_ENCRYPT -> {
                acmeManager.getCertificateOptions()?.let {
                    options.setSsl(true).setKeyCertOptions(it)
                    canStart = true
                } ?: logger.warn("Let's Encrypt certificates are not found locally. Requesting new ones...")
            }

            else -> {}
        }
        return options to canStart
    }
}