package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration37To38 : ConfigMigration(
    37,
    38,
    "Added update-source"
) {
    override fun migrate(config: JsonObject) {
        if (!config.containsKey("update-source")) {
            config.put("update-source", "AUTO")
        }
    }
}
