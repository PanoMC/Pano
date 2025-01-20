package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigMigration

@Migration
class ConfigMigration1To2 : ConfigMigration(1, 2, "Convert access_token to access-token") {
    override fun migrate(configManager: ConfigManager) {
        val panoAccountJsonObject = configManager.getConfig().getJsonObject("pano-account")

        panoAccountJsonObject.put("access-token", panoAccountJsonObject.getString("access_token"))
        panoAccountJsonObject.remove("access_token")
    }
}