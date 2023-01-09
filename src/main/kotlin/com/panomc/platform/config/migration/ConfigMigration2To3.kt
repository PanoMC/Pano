package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration
import com.panomc.platform.util.KeyGeneratorUtil
import java.util.*

@Migration
class ConfigMigration2To3(
    override val FROM_VERSION: Int = 2,
    override val VERSION: Int = 3,
    override val VERSION_INFO: String = "Add jwt-key config"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val key = KeyGeneratorUtil.generateJWTKey()

        configManager.getConfig().putAll(
            mapOf(
                "jwt-key" to Base64.getEncoder().encode(key.toByteArray())
            )
        )
    }
}