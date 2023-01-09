package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration
import com.panomc.platform.util.KeyGeneratorUtil
import java.util.*

@Migration
class ConfigMigration14To15(
    override val FROM_VERSION: Int = 14,
    override val VERSION: Int = 15,
    override val VERSION_INFO: String = "Convert jwt-keys from RSA to HS512 algorithm"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val oldConfig = configManager.getConfig().copy()
        val newConfig = mutableMapOf<String, Any>()

        configManager.getConfig().clear()

        oldConfig.forEach { field ->
            if (field.key == "jwt-keys") {
                val key = KeyGeneratorUtil.generateJWTKey()

                newConfig["jwt-key"] = Base64.getEncoder().encode(key.toByteArray())
            } else {
                newConfig[field.key] = field.value
            }
        }

        configManager.getConfig().putAll(newConfig)
    }
}