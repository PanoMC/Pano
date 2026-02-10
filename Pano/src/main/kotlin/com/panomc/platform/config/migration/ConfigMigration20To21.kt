package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration20To21 : ConfigMigration(20, 21, "Add console history limit setting.") {
    override fun migrate(config: JsonObject) {
        if (!config.containsKey("console-history-limit")) {
            config.put("console-history-limit", 50)
        }
    }
}
