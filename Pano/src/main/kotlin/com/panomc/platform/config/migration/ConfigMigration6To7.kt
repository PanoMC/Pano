package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration6To7 : ConfigMigration(6, 7, "Add server options") {
    override fun migrate(configManager: ConfigManager) {
        val config = configManager.getConfig()

        val serverConfig = JsonObject()

        serverConfig.put("host", "0.0.0.0")
        serverConfig.put("port", 8088)

        config.put("server", serverConfig)
    }
}