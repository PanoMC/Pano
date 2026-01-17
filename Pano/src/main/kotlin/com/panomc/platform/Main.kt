package com.panomc.platform

import com.panomc.platform.annotation.Boot
import com.panomc.platform.api.PluginDatabaseManager
import com.panomc.platform.auth.PermissionRegistry
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
import io.vertx.core.VertxOptions
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
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
            if (mode != "DEVELOPMENT" && System.getenv("EnvironmentType").isNullOrEmpty())
                EnvironmentType.RELEASE
            else
                EnvironmentType.DEVELOPMENT

        val VERSION by lazy {
            try {
                manifest.mainAttributes.getValue("VERSION").toString()
            } catch (e: Exception) {
                System.getenv("PanoVersion").toString()
            }
        }

        val STAGE by lazy {
            ReleaseStage.valueOf(
                stage =
                try {
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

        var IS_GUI = false
            private set

        var STARTUP_ARGS: Array<String> = emptyArray()
            private set

        @JvmStatic
        fun main(args: Array<String>) {
            STARTUP_ARGS = args
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

            vertx.deployVerticle(Main())
        }

        enum class EnvironmentType {
            DEVELOPMENT, RELEASE
        }

        lateinit var applicationContext: AnnotationConfigApplicationContext
    }

    private val logger by lazy {
        LoggerFactory.getLogger("Pano")
    }

    private lateinit var router: Router
    private lateinit var configManager: ConfigManager
    private lateinit var pluginManager: PluginManager
    private lateinit var uiManager: UIManager
    private lateinit var i18nManager: I18nManager
    private lateinit var acmeManager: AcmeManager
    private var stopping = false

    suspend fun shutdown() {
        if (stopping) {
            return
        }

        stopping = true

        logger.info("Gracefully shutting down Pano...")
        try {
            vertx.close().coAwait()
        } catch (e: Exception) {
            logger.error("Pano graceful shutdown failed", e)
        }
    }

    private fun hookCommands() {
        UiConsole.setCommandHandler { cmd ->
            // Parse & run your commands here
            when (cmd) {
                "stop", "exit", "quit" -> {
                    runBlocking { shutdown() }
                }

                else -> println("Unknown command: $cmd")
            }
        }
    }

    private fun hookShutdown() {
        UiConsole.setInterruptHandler {
            // Graceful stop
            shutdown()
            // do not System.exit(); window stays open
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking {
                shutdown()
            }
        })

//         In Unix catching SIGINT/SIGTERM (optional, not exists in Windows)
        try {
            val sigInt = Class.forName("sun.misc.Signal")
            val sigHdl = Class.forName("sun.misc.SignalHandler")
            val handleMethod = sigInt.getMethod("handle", sigInt, sigHdl)
            val ctor = sigInt.getConstructor(String::class.java)
            val handler = java.lang.reflect.Proxy.newProxyInstance(
                sigHdl.classLoader, arrayOf(sigHdl)
            ) { _, _, _ ->
                runBlocking {
                    shutdown()
                }
            }
            handleMethod.invoke(null, ctor.newInstance("INT"), handler)
            handleMethod.invoke(null, ctor.newInstance("TERM"), handler)
        } catch (_: Throwable) {
            // Windows don't have, no problem; shutdown hook is enough
        }
    }

    override suspend fun start() {
        println(
            "\n" +
                    " ______   ______     __   __     ______    \n" +
                    "/\\  == \\ /\\  __ \\   /\\ \"-.\\ \\   /\\  __ \\   \n" +
                    "\\ \\  _-/ \\ \\  __ \\  \\ \\ \\-.  \\  \\ \\ \\/\\ \\  \n" +
                    " \\ \\_\\    \\ \\_\\ \\_\\  \\ \\_\\\\\"\\_\\  \\ \\_____\\ \n" +
                    "  \\/_/     \\/_/\\/_/   \\/_/ \\/_/   \\/_____/  v${VERSION}\n" +
                    "                                           "
        )
        logger.info("Hello World!")

        init()

        startWebServer()
    }

    override suspend fun stop() {
        if (::uiManager.isInitialized) {
            uiManager.shutdown()
        }

        UiConsole.markStopped()
        exitProcess(0)
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
        executeBlocking {
            initMariaDBManager()
        }

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

        i18nManager = applicationContext.getBean(I18nManager::class.java)

        i18nManager.init()
    }

    private suspend fun initUpdateManager() {
        logger.info("Initializing update manager")

        val updateManager = applicationContext.getBean(UpdateManager::class.java)

        updateManager.init()
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

    private fun clearTempFiles() {
        val uploadsFolderTempFolder = File(configManager.config.fileUploadsFolder + File.separator + "temp")

        uploadsFolderTempFolder.deleteRecursively()

        File(AppConstants.TEMP_FOLDER).deleteRecursively()
    }

    private fun initDependencyInjection() {
        logger.info("Initializing dependency injection")

        SpringConfig.setDefaults(vertx, logger)

        applicationContext = AnnotationConfigApplicationContext(SpringConfig::class.java)
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
            println(e)
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

    private fun initMariaDBManager() {
        val setupManager = applicationContext.getBean(SetupManager::class.java)
        if (configManager.config.database.type == "portable" && setupManager.isSetupDone()) {
             logger.info("Starting Portable MariaDB...")
             val mariaDBManager = applicationContext.getBean(MariaDBManager::class.java)
             mariaDBManager.start()
        }
    }

    private suspend fun initDatabaseManager() {
        logger.info("Initializing database manager")

        val databaseManager = applicationContext.getBean(DatabaseManager::class.java)

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

        if (serverConfig.sslMode == PanoConfig.Companion.SslMode.DISABLED) {
            startHttpServer(serverConfig, router)
        } else {
            val httpHandler = if (serverConfig.redirectHttps) getHttpRedirectHandler(serverConfig) else router
            startHttpServer(serverConfig, httpHandler)
            startHttpsServer(serverConfig)
        }
    }

    private fun startHttpServer(serverConfig: PanoConfig.Companion.ServerConfig, handler: Handler<HttpServerRequest>) {
        val host = serverConfig.host
        val port = serverConfig.httpPort
        val isSslEnabled = serverConfig.sslMode != PanoConfig.Companion.SslMode.DISABLED

        logger.info("Creating HTTP server on port $port")
        vertx.createHttpServer()
            .requestHandler(handler)
            .listen(port, host)
            .onSuccess {
                if (isSslEnabled) {
                    logger.info("HTTP server is listening on $port. Ready for ACME challenges if needed.")
                    if (serverConfig.sslMode == PanoConfig.Companion.SslMode.LETS_ENCRYPT) {
                        acmeManager.prepareCertificates()
                    }
                } else {
                    logger.info("Started listening on http://$host:$port, ready to rock & roll! (${TimeUtil.getStartupTime()}s)")
                    UiConsole.markReady()
                }
            }
            .onFailure { result ->
                val message = "Failed to listen on http://$host:$port, reason: ${result.message ?: result.toString()}"
                if (isSslEnabled) {
                    logger.warn(message)
                    UiConsole.markReady()
                } else {
                    logger.error(message)
                    exitProcess(1)
                }
            }
    }

    private fun startHttpsServer(serverConfig: PanoConfig.Companion.ServerConfig) {
        val host = serverConfig.host
        val port = serverConfig.httpsPort
        val (options, canStart) = prepareHttpsOptions(serverConfig)

        if (!canStart) {
            UiConsole.markReady()
            return
        }

        logger.info("Creating HTTPS server on port $port (Mode: ${serverConfig.sslMode})")
        vertx.createHttpServer(options)
            .requestHandler(router)
            .listen(port, host)
            .onSuccess {
                logger.info("Started listening on https://$host:$port, ready to rock & roll! (${TimeUtil.getStartupTime()}s)")
                UiConsole.markReady()
            }
            .onFailure { result ->
                logger.error("Failed to listen on https://$host:$port, reason: ${result.message ?: result.toString()}")
                UiConsole.markReady()
            }
    }

    private fun getHttpRedirectHandler(serverConfig: PanoConfig.Companion.ServerConfig): Handler<HttpServerRequest> {
        return Handler { req ->
            if (req.path().startsWith("/.well-known/acme-challenge/")) {
                router.handle(req)
            } else {
                val hostHeader = req.getHeader("Host")
                val domain = if (hostHeader != null) {
                    if (hostHeader.startsWith("[")) hostHeader.substringBefore("]:") + "]" else hostHeader.substringBefore(":")
                } else {
                    try { java.net.URI(configManager.config.websiteUrl).host } catch (e: Exception) { null }
                }

                if (domain == null || domain == "0.0.0.0") {
                    router.handle(req)
                } else {
                    val port = serverConfig.httpsPort
                    val portSuffix = if (port == 443) "" else ":$port"
                    val query = if (req.query().isNullOrBlank()) "" else "?" + req.query()
                    val redirectTo = "https://$domain$portSuffix${req.path()}$query"

                    req.response()
                        .setStatusCode(301)
                        .putHeader("Location", redirectTo)
                        .end()
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
                        options.setSsl(true)
                            .setKeyCertOptions(PemKeyCertOptions()
                                .setCertValue(Buffer.buffer(cert))
                                .setKeyValue(Buffer.buffer(key)))
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