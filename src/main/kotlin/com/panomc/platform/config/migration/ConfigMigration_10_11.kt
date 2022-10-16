package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration

@Suppress("ClassName")
@Migration
class ConfigMigration_10_11(
    override val FROM_VERSION: Int = 10,
    override val VERSION: Int = 11,
    override val VERSION_INFO: String = "Add support e-mail field"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val oldConfig = configManager.getConfig().copy()
        val newConfig = mutableMapOf<String, Any>()

        configManager.getConfig().clear()

        oldConfig.forEach { field ->
            newConfig[field.key] = field.value

            if (field.key == "website-description") {
                newConfig["support-email"] = ""
            }
        }

        configManager.getConfig().putAll(newConfig)
    }
}