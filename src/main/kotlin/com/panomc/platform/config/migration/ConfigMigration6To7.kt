package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration

@Migration
class ConfigMigration6To7(
    override val FROM_VERSION: Int = 6,
    override val VERSION: Int = 7,
    override val VERSION_INFO: String = "Add server ip-address field"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val oldConfig = configManager.getConfig().copy()
        val newConfig = mutableMapOf<String, Any>()

        configManager.getConfig().clear()

        oldConfig.forEach { field ->
            newConfig[field.key] = field.value

            if (field.key == "website-description") {
                newConfig["server-ip-address"] = "play.ipaddress.com"
            }
        }

        configManager.getConfig().putAll(newConfig)
    }
}