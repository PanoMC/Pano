package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigMigration

@Migration
class ConfigMigration3To4 : ConfigMigration(3, 4, "Add platform-id to pano-account section") {
    override fun migrate(configManager: ConfigManager) {
        val panoAccountJsonObject = configManager.getConfig().getJsonObject("pano-account")

        panoAccountJsonObject.put("platform-id", "")
    }
}