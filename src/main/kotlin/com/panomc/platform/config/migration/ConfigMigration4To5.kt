package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration
import com.panomc.platform.util.UpdatePeriod

@Migration
class ConfigMigration4To5(
    override val FROM_VERSION: Int = 4,
    override val VERSION: Int = 5,
    override val VERSION_INFO: String = "Add update-period field"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        configManager.getConfig().putAll(
            mapOf(
                "update-period" to UpdatePeriod.ONCE_PER_DAY.period
            )
        )
    }
}