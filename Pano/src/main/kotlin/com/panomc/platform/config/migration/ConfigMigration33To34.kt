package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration33To34 : ConfigMigration(
    33,
    34,
    "Added managed-servers.node-auto-update"
) {
    override fun migrate(config: JsonObject) {
        // Into the existing block, keeping whatever plugin-jar-dir it holds. A block that was
        // hand-removed is written back whole, for the same reason ConfigMigration31To32 writes it:
        // PanoConfig has no no-arg constructor, so a missing block deserialises to null.
        val managedServers = config.getJsonObject("managed-servers")
            ?: JsonObject().put("plugin-jar-dir", null as String?).also { config.put("managed-servers", it) }

        if (!managedServers.containsKey("node-auto-update")) {
            managedServers.put("node-auto-update", true)
        }
    }
}
