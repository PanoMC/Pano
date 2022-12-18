package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration

@Migration
class ConfigMigration12To13(
    override val FROM_VERSION: Int = 12,
    override val VERSION: Int = 13,
    override val VERSION_INFO: String = "Add game server version field"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val oldConfig = configManager.getConfig().copy()
        val newConfig = mutableMapOf<String, Any>()

        configManager.getConfig().clear()

        oldConfig.forEach { field ->
            newConfig[field.key] = field.value

            if (field.key == "server-ip-address") {
                newConfig["server-game-version"] = "1.8.x"
            }
        }

        configManager.getConfig().putAll(newConfig)
    }
}