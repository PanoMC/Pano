package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration12To13 : ConfigMigration(12, 13, "Added allow-user-locale-selection option") {
    override fun migrate(config: JsonObject) {
        config.put("allow-user-locale-selection", true)
    }
}