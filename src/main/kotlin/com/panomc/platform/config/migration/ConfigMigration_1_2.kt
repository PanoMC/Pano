package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration

@Suppress("ClassName")
@Migration
class ConfigMigration_1_2(
    override val FROM_VERSION: Int = 1,
    override val VERSION: Int = 2,
    override val VERSION_INFO: String = "Add mail config"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        configManager.getConfig().putAll(
            mapOf(
                "email" to mapOf(
                    "address" to "",
                    "host" to "",
                    "port" to 465,
                    "username" to "",
                    "password" to "",
                    "SSL" to true
                )
            )
        )
    }
}