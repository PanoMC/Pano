package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration2To3 : ConfigMigration(2, 3, "Add Pano API url") {
    override fun migrate(config: JsonObject) {
        config.put("pano-api-url", "api.panomc.com")
    }
}