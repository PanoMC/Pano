package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigMigration

@Migration
class ConfigMigration2To3 : ConfigMigration(2, 3, "Add Pano API url") {
    override fun migrate(configManager: ConfigManager) {
        configManager.getConfig().put("pano-api-url", "https://api.panomc.com")
    }
}