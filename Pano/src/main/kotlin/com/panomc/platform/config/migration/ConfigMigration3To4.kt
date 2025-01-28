package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration3To4 : ConfigMigration(3, 4, "Add platform-id to pano-account section") {
    override fun migrate(config: JsonObject) {
        val panoAccountJsonObject = config.getJsonObject("pano-account")

        panoAccountJsonObject.put("platform-id", "")
    }
}