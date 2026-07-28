package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration25To26 : ConfigMigration(
    25,
    26,
    "Added maintenance mode settings and the trusted proxy list"
) {
    override fun migrate(config: JsonObject) {
        // Every key is written explicitly: PanoConfig has no no-arg constructor, so Gson
        // allocates it via Unsafe and a key missing from config.conf would deserialise to
        // null/false instead of the Kotlin default.
        val maintenanceConfig = JsonObject()

        maintenanceConfig.put("enabled", false)
        maintenanceConfig.put("bypass-permission-node", "")
        maintenanceConfig.put("show-login-button", true)
        maintenanceConfig.put("custom-login-url", "")
        maintenanceConfig.put("show-site-logo", true)
        maintenanceConfig.put("title", "")
        maintenanceConfig.put("message-html", "")
        maintenanceConfig.put("custom-css", "")
        maintenanceConfig.put("max-login-attempts", 3)

        config.put("maintenance", maintenanceConfig)

        val serverConfig = config.getJsonObject("server") ?: JsonObject()

        if (!serverConfig.containsKey("trusted-proxies")) {
            serverConfig.put("trusted-proxies", JsonArray())
        }

        config.put("server", serverConfig)
    }
}
