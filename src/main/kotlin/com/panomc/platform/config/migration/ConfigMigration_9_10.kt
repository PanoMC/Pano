package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration

@Suppress("ClassName")
@Migration
class ConfigMigration_9_10(
    override val FROM_VERSION: Int = 9,
    override val VERSION: Int = 10,
    override val VERSION_INFO: String = "Add ui-address field"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val oldConfig = configManager.getConfig().copy()
        val newConfig = mutableMapOf<String, Any>()

        configManager.getConfig().clear()

        oldConfig.forEach { field ->
            newConfig[field.key] = field.value

            if (field.key == "update-period") {
                newConfig["ui-address"] = "http://localhost:3000"
            }
        }

        configManager.getConfig().putAll(newConfig)
    }
}