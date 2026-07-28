package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration26To27 : ConfigMigration(
    26,
    27,
    "Added the maintenance mode full-page editor flag"
) {
    override fun migrate(config: JsonObject) {
        // Written explicitly rather than left to the Kotlin default: PanoConfig has no no-arg
        // constructor, so Gson allocates it via Unsafe and a missing key deserialises to false
        // anyway — but only by accident. Existing installations are all on the composed page.
        val maintenanceConfig = config.getJsonObject("maintenance") ?: JsonObject()

        if (!maintenanceConfig.containsKey("custom-page")) {
            maintenanceConfig.put("custom-page", false)
        }

        config.put("maintenance", maintenanceConfig)
    }
}
