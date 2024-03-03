package com.panomc.platform

import com.panomc.platform.annotation.Boot
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.server.ServerManager
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.TimeUtil
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.CoroutineVerticle
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

        private val mode by lazy {
            try {
                val urlClassLoader = ClassLoader.getSystemClassLoader()
                val manifestUrl = urlClassLoader.getResourceAsStream("META-INF/MANIFEST.MF")
                val manifest = Manifest(manifestUrl)

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
                val urlClassLoader = ClassLoader.getSystemClassLoader()
                val manifestUrl = urlClassLoader.getResourceAsStream("META-INF/MANIFEST.MF")
                val manifest = Manifest(manifestUrl)

                manifest.mainAttributes.getValue("VERSION").toString()
            } catch (e: Exception) {
                System.getenv("PanoVersion").toString()
            }
        }

        val STAGE by lazy {
            ReleaseStage.valueOf(
                stage =
                try {
                    val urlClassLoader = ClassLoader.getSystemClassLoader()
                    val manifestUrl = urlClassLoader.getResourceAsStream("META-INF/MANIFEST.MF")
                    val manifest = Manifest(manifestUrl)

                    manifest.mainAttributes.getValue("BUILD_TYPE").toString()
                } catch (e: Exception) {
                    System.getenv("PanoBuildType").toString()
                }
            )!!
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

    private suspend fun init() {
        initDependencyInjection()

        initPlugins()

        initConfigManager()

        clearTempFiles()

        val isPlatformInstalled = initSetupManager()

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


//        pluginManager.plugins

        logger.info("Enabling plugins")

        pluginManager.startPlugins()
//        try {
//
//            // Iterate through each plugin
//            for (plugin in pluginManager.plugins) {
//                // Get the main class of the plugin
//                val mainClass = plugin.pluginClassLoader.loadClass(plugin.descriptor.pluginClass)
//
//                // Get the package of the main class
//                val mainPackage = mainClass.`package`
//
//                // Print the base package of the main class
//                if (mainPackage != null) {
//                    println("Base package of main class of plugin ${plugin.descriptor.pluginId}: ${mainPackage.name}")
////                    applicationContext.scan(mainPackage.name)
//                } else {
//                    println("Main class package not found for plugin ${plugin.descriptor.pluginId}")
//                }
//            }
////            pluginManager.plugins.map {(it.plugin as PanoPlugin).context.pluginBeanContext.getBeansWithAnnotation(Endpoint::class.java)}
//        } catch (e: Exception) {
//            logger.error(e.toString())
//        }


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