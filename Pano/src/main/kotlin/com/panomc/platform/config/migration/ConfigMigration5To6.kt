package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration5To6 : ConfigMigration(5, 6, "Add init ui option") {
    override fun migrate(config: JsonObject) {
        config.put("init-ui", true)
    }
}