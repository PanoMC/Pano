package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration

@Migration
class ConfigMigration8To9(
    override val FROM_VERSION: Int = 8,
    override val VERSION: Int = 9,
    override val VERSION_INFO: String = "Update language field to locale"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val oldConfig = configManager.getConfig().copy()
        val newConfig = mutableMapOf<String, Any>()

        configManager.getConfig().clear()

        oldConfig.forEach { field ->
            if (field.key == "language") {
                newConfig["locale"] = field.value
            } else {
                newConfig[field.key] = field.value
            }
        }

        configManager.getConfig().putAll(newConfig)
    }
}