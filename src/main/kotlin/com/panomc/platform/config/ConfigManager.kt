package com.panomc.platform.config

import com.panomc.platform.config.migration.ConfigMigration_1_2
import com.panomc.platform.config.migration.ConfigMigration_2_3
import com.panomc.platform.config.migration.ConfigMigration_3_4
import com.panomc.platform.util.KeyGeneratorUtil
import io.jsonwebtoken.io.Encoders
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.Logger
import io.vertx.kotlin.config.getConfigAwait
import kotlinx.coroutines.runBlocking
import java.io.File

class ConfigManager(mLogger: Logger, mVertx: Vertx) {

    private val mMigrations = listOf(
        ConfigMigration_1_2(),
        ConfigMigration_2_3(),
        ConfigMigration_3_4()
    )

    private val mConfig = com.beust.klaxon.JsonObject()

    private val mConfigFile = File("config.json")

    private val mIsFileConfig = when {
        mConfigFile.exists() -> true
        mConfigFile.createNewFile() -> {
            mConfigFile.writeText(DEFAULT_CONFIG.toJsonString(true))

            true
        }
        else -> false
    }

    companion object {
        private const val CONFIG_VERSION = 3

        private val DEFAULT_CONFIG by lazy {
            val key = KeyGeneratorUtil.generateJWTKeys()

            JsonObject(
                mapOf(
                    "config-version" to CONFIG_VERSION,
                    "development-mode" to true,

                    "website-name" to "",
                    "website-description" to "",

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
                    )
                )
            )
        }
    }

    init {
        if (mIsFileConfig) {
            val fileStore = ConfigStoreOptions()
                .setType("file")
                .setConfig(JsonObject().put("path", "config.json"))

            val options = ConfigRetrieverOptions().addStore(fileStore)

            val retriever = ConfigRetriever.create(mVertx, options)

            loadConfigFromFile(retriever)

            migrate()

            loadConfigFromFile(retriever)

            retriever.listen { change ->
                mConfig.clear()

                mConfig.putAll(change.newConfiguration.map)
            }
        } else {
            mConfig.clear()

            mConfig.putAll(DEFAULT_CONFIG.map)
        }
    }

    private fun loadConfigFromFile(retriever: ConfigRetriever) {
        runBlocking {
            mConfig.clear()

            mConfig.putAll(retriever.getConfigAwait().map)
        }
    }

    private fun migrate() {
        if (getConfigVersion() != CONFIG_VERSION) {
            val listOfMigratableMigrations = mMigrations
                .filter { configMigration -> configMigration.isMigratable(getConfigVersion()) }

            listOfMigratableMigrations
                .forEach {
                    getConfig()["config-version"] = it.VERSION

                    it.migrate(this)
                }

            if (listOfMigratableMigrations.isNotEmpty())
                saveConfig()
        }
    }

    fun JsonObject.toJsonString(prettyPrint: Boolean = false, canonical: Boolean = false): String {
        val jsonObject = com.beust.klaxon.JsonObject()

        jsonObject.putAll(this.map)

        return jsonObject.toJsonString(prettyPrint, canonical)
    }

    fun saveConfig() {
        mConfigFile.writeText(mConfig.toJsonString(true))
    }

    fun getConfigVersion() = mConfig["config-version"] as Int

    fun getConfig() = mConfig
}