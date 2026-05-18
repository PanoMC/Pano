package com.panomc.platform.api.config

import com.google.gson.Gson
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Migration
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.config.HoconWriter
import com.typesafe.config.ConfigFactory
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.json.JsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

class PluginConfigManager<T : PluginConfig>(
    private val plugin: PanoPlugin,
    private val pluginConfigClass: Class<T>
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val gson = Gson()

    private val pluginId = plugin.pluginId

    private val pluginManager by lazy {
        plugin.applicationContext.getBean(PluginManager::class.java)
    }

    private val pluginsFolder = pluginManager.pluginsRoot.toAbsolutePath().toString()
    private val pluginsDataDir = System.getProperty("pano.pluginDataDir", pluginsFolder)

    val configFilePath = pluginsDataDir + File.separator + pluginId + File.separator + "config.conf"

    val configFile = File(configFilePath)

    private val fileStore = ConfigStoreOptions()
        .setType("file")
        .setFormat("hocon")
        .setConfig(JsonObject().put("path", configFilePath))

    private val options = ConfigRetrieverOptions().addStore(fileStore)

    private val configRetriever = ConfigRetriever.create(plugin.vertx, options)

    private val migrations by lazy {
        val beans = plugin.pluginBeanContext.getBeansWithAnnotation(Migration::class.java)

        beans.filter { it.value is PluginConfigMigration }.map { it.value as PluginConfigMigration }
            .sortedBy { it.from }
    }

    lateinit var config: T
        private set

    init {
        initialize()
        migrate()
    }

    fun saveConfig(config: JsonObject) {
        if (configFile.parentFile != null && !configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }

        configFile.writeText(HoconWriter.render(config, pluginConfigClass))

        updateConfig(config)
    }

    private fun getLastVersion() = migrations.maxByOrNull { it.to }?.to ?: 1

    private fun initialize() {
        logger.info("Initializing config")

        if (configFile.parentFile != null && !configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }

        if (!configFile.exists()) {
            logger.warn("Couldn't find config. Saving default config")

            val config = JsonObject.mapFrom(gson.fromJson(JsonObject().toString(), pluginConfigClass))

            config.put("version", getLastVersion())

            saveConfig(config)

            return
        }

        val config = ConfigFactory.parseFile(configFile)

        updateConfig(JsonObject(config.root().unwrapped()))
    }

    private fun migrate(configAsJsonObject: JsonObject = JsonObject(gson.toJson(config)), configVersion: Int = config.version, saveConfig: Boolean = false) {
        logger.info("Checking available config migrations")

        migrations
            .find { configMigration -> configMigration.isMigratable(configVersion) }
            ?.let { migration ->
                logger.info("Migration Found! Migrating config from version ${migration.from} to ${migration.to}: ${migration.versionInfo}")

                configAsJsonObject.put("version", migration.to)

                migration.migrate(configAsJsonObject)

                migrate(configAsJsonObject, migration.to, true)

                return
            }

        if (saveConfig) {
            saveConfig(configAsJsonObject)
        }
    }

    fun listen() {
        configRetriever.listen { change ->
            updateConfig(change.newConfiguration)
        }
    }

    fun close() {
        configRetriever.close()
    }

    private fun updateConfig(newConfig: JsonObject) {
        config = gson.fromJson(newConfig.toString(), pluginConfigClass)
    }
}