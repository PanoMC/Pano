package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration8To9 : ConfigMigration(8, 9, "Add enabled option in email configuration") {
    override fun migrate(config: JsonObject) {
        val emailConfig = config.getJsonObject("email")

        emailConfig.put("enabled", false)
    }
}