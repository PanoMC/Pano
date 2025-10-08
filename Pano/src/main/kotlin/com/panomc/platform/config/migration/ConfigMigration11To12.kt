package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration11To12 : ConfigMigration(11, 12, "Added website-url") {
    override fun migrate(config: JsonObject) {
        config.put("website-url", "")
    }
}