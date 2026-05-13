package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration23To24 : ConfigMigration(
    23,
    24,
    "Added website-url-redirect toggle for canonical-host redirects"
) {
    override fun migrate(config: JsonObject) {
        config.put("website-url-redirect", true)
    }
}
