package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration9To10 : ConfigMigration(9, 10, "Add pano website url") {
    override fun migrate(config: JsonObject) {
        config.put("pano-website-url", "https://dev.panomc.com")
    }
}