package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration39To40 : ConfigMigration(
    39,
    40,
    "Added webhooks"
) {
    override fun migrate(config: JsonObject) {
        // Written whole, like ConfigMigration38To39: PanoConfig has no no-arg constructor, so a
        // block missing from config.conf deserialises to null rather than to the Kotlin defaults.
        // Private targets stay refused, which is what webhooks did before this setting existed.
        if (!config.containsKey("webhooks")) {
            config.put("webhooks", JsonObject().put("allow-private-targets", false))
        }
    }
}
