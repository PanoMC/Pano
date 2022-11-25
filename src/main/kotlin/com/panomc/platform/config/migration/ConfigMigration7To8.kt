package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration

@Migration
class ConfigMigration7To8(
    override val FROM_VERSION: Int = 7,
    override val VERSION: Int = 8,
    override val VERSION_INFO: String = "Add language field"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val oldConfig = configManager.getConfig().copy()
        val newConfig = mutableMapOf<String, Any>()

        configManager.getConfig().clear()

        oldConfig.forEach { field ->
            newConfig[field.key] = field.value

            if (field.key == "development-mode") {
                newConfig["language"] = "en-US"
            }
        }

        configManager.getConfig().putAll(newConfig)
    }
}