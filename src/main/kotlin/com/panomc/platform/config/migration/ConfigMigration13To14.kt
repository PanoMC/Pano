package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration

@Migration
class ConfigMigration13To14(
    override val FROM_VERSION: Int = 13,
    override val VERSION: Int = 14,
    override val VERSION_INFO: String = "Convert access_token to access-token"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val oldConfig = configManager.getConfig().copy()
        val newConfig = mutableMapOf<String, Any>()

        configManager.getConfig().clear()

        oldConfig.forEach { field ->
            if (field.key == "access_token") {
                newConfig["access-token"] = field.value
            } else {
                newConfig[field.key] = field.value
            }
        }

        configManager.getConfig().putAll(newConfig)
    }
}