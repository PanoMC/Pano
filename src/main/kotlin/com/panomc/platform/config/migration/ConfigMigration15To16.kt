package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration15To16(
    override val FROM_VERSION: Int = 15,
    override val VERSION: Int = 16,
    override val VERSION_INFO: String = "Add TLS and auth method options to under mail config"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val oldConfig = configManager.getConfig().copy()
        val newConfig = mutableMapOf<String, Any>()

        configManager.getConfig().clear()

        oldConfig.forEach { field ->
            if (field.key == "email") {
                (field.value as JsonObject).put("TLS", false).put("auth-method", "")
            }

            newConfig[field.key] = field.value
        }

        configManager.getConfig().putAll(newConfig)
    }
}