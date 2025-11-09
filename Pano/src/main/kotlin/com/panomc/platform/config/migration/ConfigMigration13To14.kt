package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration13To14 : ConfigMigration(13, 14, "Added accept plugin auth option") {
    override fun migrate(config: JsonObject) {
        config.put("accept-plugin-auth", true)
    }
}