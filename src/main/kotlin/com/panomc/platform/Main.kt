package com.panomc.platform

import com.panomc.platform.annotation.Boot
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.util.SetupManager
import com.panomc.platform.util.TimeUtil
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
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

        @JvmStatic
        fun main(args: Array<String>) {
            vertx.deployVerticle(Main())
        }

        enum class EnvironmentType {
            DEVELOPMENT, RELEASE
        }
    }

    private val logger by lazy {
        LoggerFactory.getLogger("Pano")
    }

    private lateinit var router: Router
    private lateinit var applicationContext: AnnotationConfigApplicationContext

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
        logger.info("Initializing dependency injection...")

        SpringConfig.setDefaults(vertx, logger)

        applicationContext = AnnotationConfigApplicationContext(SpringConfig::class.java)

        router = applicationContext.getBean(Router::class.java)
        val configManager = applicationContext.getBean(ConfigManager::class.java)
        val databaseManager = applicationContext.getBean(DatabaseManager::class.java)
        val setupManager = applicationContext.getBean(SetupManager::class.java)

        logger.info("Initializing config manager...")

        configManager.init()

        if (!setupManager.isSetupDone()) {
            logger.info("Platform is not installed! Skipping database manager initializing...")

            return
        }

        logger.info("Platform is installed.")

        logger.info("Initializing database manager...")

        databaseManager.init()
    }

    private fun startWebServer() {
        logger.info("Creating HTTP server...")

        vertx
            .createHttpServer()
            .requestHandler(router)
            .listen(PORT) { result ->
                if (result.succeeded()) {
                    logger.info("Started listening on port $PORT, and ready to rock & roll! (${TimeUtil.getStartupTime()}s)")
                } else {
                    logger.error("Failed to listen on port $PORT, reason: " + result.cause().toString())
                }
            }
    }
}