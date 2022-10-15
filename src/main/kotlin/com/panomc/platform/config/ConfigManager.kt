package com.panomc.platform.config

import com.panomc.platform.annotation.Migration
import com.panomc.platform.util.KeyGeneratorUtil
import com.panomc.platform.util.UpdatePeriod
import io.jsonwebtoken.io.Encoders
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import org.slf4j.Logger
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.io.File

class ConfigManager(vertx: Vertx, private val logger: Logger, applicationContext: AnnotationConfigApplicationContext) {

    companion object {
        private const val CONFIG_VERSION = 10

        private val DEFAULT_CONFIG by lazy {
            val key = KeyGeneratorUtil.generateJWTKeys()

            JsonObject(
                mapOf(
                    "config-version" to CONFIG_VERSION,
                    "development-mode" to true,
                    "locale" to "en-US",

                    "website-name" to "",
                    "website-description" to "",
                    "server-ip-address" to "play.ipadress.com",
                    "keywords" to listOf<String>(),

                    "setup" to mapOf(
                        "step" to 0
                    ),

                    "database" to mapOf(
                        "host" to "",
                        "name" to "",
                        "username" to "",
                        "password" to "",
                        "prefix" to "pano_"
                    ),

                    "pano-account" to mapOf(
                        "username" to "",
                        "email" to "",
                        "access-token" to ""
                    ),

                    "current-theme" to "Vanilla",

                    "email" to mapOf(
                        "address" to "",
                        "host" to "",
                        "port" to 465,
                        "username" to "",
                        "password" to "",
                        "SSL" to true
                    ),

                    "jwt-keys" to mapOf(
                        "private" to Encoders.BASE64.encode(key.private.encoded),
                        "public" to Encoders.BASE64.encode(key.public.encoded)
                    ),

                    "update-period" to UpdatePeriod.ONCE_PER_DAY.period,

                    "url-address" to "http://localhost:3000"
                )
            )
        }

        fun JsonObject.putAll(jsonObject: Map<String, Any>) {
            jsonObject.forEach {
                this.put(it.key, it.value)
            }
        }
    }

    fun saveConfig() {
        configFile.writeText(config.encodePrettily())
    }

    fun getConfig() = config

    internal suspend fun init() {
        if (!configFile.exists()) {
            configFile.writeText(DEFAULT_CONFIG.encodePrettily())
        }

        val configValues: Map<String, Any>

        try {
            configValues = configRetriever.config.await().map
        } catch (e: Exception) {
            logger.error("Error occurred while loading config file! Error: $e")
            logger.info("Using default config!")

            config.putAll(DEFAULT_CONFIG.map)

            return
        }

        config.putAll(configValues)

        migrate()

        listenConfigFile()
    }

    private fun getConfigVersion(): Int = config.getInteger("config-version")

    private val config = JsonObject()

    private val migrations by lazy {
        val beans = applicationContext.getBeansWithAnnotation(Migration::class.java)

        beans.filter { it.value is ConfigMigration }.map { it.value as ConfigMigration }.sortedBy { it.FROM_VERSION }
    }

    private val configFile = File("config.json")

    private val fileStore = ConfigStoreOptions()
        .setType("file")
        .setConfig(JsonObject().put("path", "config.json"))

    private val options = ConfigRetrieverOptions().addStore(fileStore)

    private val configRetriever = ConfigRetriever.create(vertx, options)

    private fun migrate() {
        logger.info("Checking available config migrations")

        var migrateCount = 0

        if (getConfigVersion() != CONFIG_VERSION) {
            var migration = migrations.find { configMigration -> configMigration.isMigratable(getConfigVersion()) }

            while (migration != null) {
                logger.info("Migration Found! Migrating config from version ${migration.FROM_VERSION} to ${migration.VERSION}: ${migration.VERSION_INFO}")

                migrateCount++

                config.put("config-version", migration.VERSION)

                migration.migrate(this)

                migration = migrations.find { configMigration -> configMigration.isMigratable(getConfigVersion()) }
            }

            if (migrateCount != 0) {
                saveConfig()
            }
        }
    }

    private fun listenConfigFile() {
        configRetriever.listen { change ->
            config.clear()

            updateConfig(change.newConfiguration)
        }
    }

    private fun updateConfig(newConfig: JsonObject) {
        newConfig.map.forEach {
            config.put(it.key, it.value)
        }
    }
}