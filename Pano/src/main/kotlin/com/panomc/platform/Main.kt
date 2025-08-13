package com.panomc.platform

import com.panomc.platform.annotation.Boot
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.server.ServerManager
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.Architecture
import com.panomc.platform.util.OperatingSystem
import com.panomc.platform.util.TimeUtil
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
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

        @JvmStatic
        fun main(args: Array<String>) {
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
    private var stopping = false

    fun shutdown() {
        if (stopping) {
            return
        }

        stopping = true

        logger.info("Shutting down Pano...")
        try {
            vertx.close()

        } catch (e: Exception) {
            logger.error("Pano graceful shutdown failed", e)
        }
    }

    private fun hookShutdown() {
        Runtime.getRuntime().addShutdownHook(Thread {
            shutdown()
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
                shutdown()
                exitProcess(0)
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

            initPlugins()
        }

        initConfigManager()

        executeBlocking {
            clearTempFiles()
        }

        var isPlatformInstalled = false

        executeBlocking {
            isPlatformInstalled = initSetupManager()
        }

        if (isPlatformInstalled) {
            initDatabaseManager()

            initServerManager()

            initUpdateManager()
        }

        executeBlocking {
            initUiManager()

            initRoutes()
        }
    }

    private suspend fun initUpdateManager() {
        logger.info("Initializing update manager")

        val updateManager = applicationContext.getBean(UpdateManager::class.java)

        updateManager.init()
    }

    private fun initPlugins() {
        logger.info("Initializing plugin manager")

        pluginManager = applicationContext.getBean(PluginManager::class.java)

        logger.info("Loading plugins")

        pluginManager.loadPlugins()

        logger.info("Enabling plugins")

        pluginManager.startPlugins()
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

    private suspend fun initDatabaseManager() {
        logger.info("Initializing database manager")

        val databaseManager = applicationContext.getBean(DatabaseManager::class.java)

        databaseManager.init()
    }

    private suspend fun initServerManager() {
        logger.info("Initializing server manager")

        val serverManager = applicationContext.getBean(ServerManager::class.java)

        serverManager.init()
    }

    private fun initRoutes() {
        logger.info("Initializing routes")

        try {
            router = applicationContext.getBean(Router::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startWebServer() {
        logger.info("Creating HTTP server")

        val serverConfig = configManager.config.server
        val host = serverConfig.host
        val port = serverConfig.port

        vertx
            .createHttpServer()
            .requestHandler(router)
            .listen(port, host)
            .onSuccess {
                logger.info("Started listening on http://$host:$port, ready to rock & roll! (${TimeUtil.getStartupTime()}s)")
            }
            .onFailure { result ->
                logger.error("Failed to listen on http://$host:$port, reason: " + result.cause.toString())
            }
    }
}