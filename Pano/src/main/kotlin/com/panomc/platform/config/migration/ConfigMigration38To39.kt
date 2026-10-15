package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration38To39 : ConfigMigration(
    38,
    39,
    "Added frontend"
) {
    override fun migrate(config: JsonObject) {
        // Written whole, like ConfigMigration32To33: PanoConfig has no no-arg constructor, so a
        // block missing from config.conf deserialises to null rather than to the Kotlin defaults.
        // THEME is what every existing install already was, so nothing changes for it.
        if (!config.containsKey("frontend")) {
            config.put(
                "frontend",
                JsonObject()
                    .put("mode", "THEME")
                    .put("custom-app", "")
                    .put("upstream-url", "")
                    .put("site-url", "")
                    .put("descriptor-url", "")
                    .put("dev-url", "")
            )
        }
    }
}
