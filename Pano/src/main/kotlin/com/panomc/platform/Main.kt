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
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.io.File
import java.util.jar.Manifest


@Boot
class Main : CoroutineVerticle() {
    companion object {
        const val PORT = 8088

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

    private fun hookShutdown() {
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                runBlocking {
                    vertx.close().coAwait()
                }
            } catch (e: Exception) {
                logger.error("Pano graceful shutdown failed", e)
            }
        })
    }

    override suspend fun start() {
        hookShutdown()

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

    private suspend fun init() {
        initDependencyInjection()

        initPlugins()

        initConfigManager()

        clearTempFiles()

        val isPlatformInstalled = initSetupManager()

        vertx.executeBlocking { ->
            initUiManager()
        }.onFailure {
            it.printStackTrace()
        }.coAwait()

        if (isPlatformInstalled) {
            initDatabaseManager()

            initServerManager()
        }

        initRoutes()
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
        val tempFolder = File(configManager.getConfig().getString("file-uploads-folder") + "/temp")

        if (tempFolder.exists()) {
            deleteDirectory(tempFolder)
        }
    }

    private fun deleteDirectory(directoryToBeDeleted: File) {
        val allContents = directoryToBeDeleted.listFiles()

        if (allContents != null) {
            for (file in allContents) {
                deleteDirectory(file)
            }
        }

        directoryToBeDeleted.delete()
    }

    private fun initDependencyInjection() {
        logger.info("Initializing dependency injection")

        SpringConfig.setDefaults(vertx, logger)

        applicationContext = AnnotationConfigApplicationContext(SpringConfig::class.java)
    }

    private fun initUiManager() {
        logger.info("Initializing UI manager")

        val uiManager = applicationContext.getBean(UIManager::class.java)

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

        router = applicationContext.getBean(Router::class.java)
    }

    private fun startWebServer() {
        logger.info("Creating HTTP server")

        vertx
            .createHttpServer()
            .requestHandler(router)
            .listen(PORT) { result ->
                if (result.succeeded()) {
                    logger.info("Started listening on port $PORT, ready to rock & roll! (${TimeUtil.getStartupTime()}s)")
                } else {
                    logger.error("Failed to listen on port $PORT, reason: " + result.cause().toString())
                }
            }
    }
}