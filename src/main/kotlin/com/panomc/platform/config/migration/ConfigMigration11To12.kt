package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration

@Migration
class ConfigMigration11To12(
    override val FROM_VERSION: Int = 11,
    override val VERSION: Int = 12,
    override val VERSION_INFO: String = "Add file uploads folder and file paths fields"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val oldConfig = configManager.getConfig().copy()
        val newConfig = mutableMapOf<String, Any>()

        configManager.getConfig().clear()

        oldConfig.forEach { field ->
            newConfig[field.key] = field.value

            if (field.key == "ui-address") {
                newConfig["file-uploads-folder"] = "file-uploads"
                newConfig["file-paths"] = mapOf<String, String>()
            }
        }

        configManager.getConfig().putAll(newConfig)
    }
}