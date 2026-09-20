package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration28To29 : ConfigMigration(
    28,
    29,
    "Added telemetry settings"
) {
    override fun migrate(config: JsonObject) {
        // Every key is written explicitly: PanoConfig has no no-arg constructor, so Gson
        // allocates it via Unsafe and a key missing from config.conf would deserialise to
        // null/false instead of the Kotlin default.
        val telemetryConfig = JsonObject()

        telemetryConfig.put("enabled", true)

        config.put("telemetry", telemetryConfig)
    }
}
