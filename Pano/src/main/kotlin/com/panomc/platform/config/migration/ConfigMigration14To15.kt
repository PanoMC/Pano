package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration14To15 : ConfigMigration(14, 15, "Remove ui-address from config") {
    override fun migrate(config: JsonObject) {
        config.remove("ui-address")
    }
}