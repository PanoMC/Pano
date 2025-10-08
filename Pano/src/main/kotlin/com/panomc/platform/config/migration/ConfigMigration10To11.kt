package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration10To11 : ConfigMigration(10, 11, "Added register-agreement") {
    override fun migrate(config: JsonObject) {
        config.put("register-agreement", "")
    }
}