package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigMigration

@Migration
class ConfigMigration5To6 : ConfigMigration(5, 6, "Add init ui option") {
    override fun migrate(configManager: ConfigManager) {
        val config = configManager.getConfig()

        config.put("init-ui", true)
    }
}