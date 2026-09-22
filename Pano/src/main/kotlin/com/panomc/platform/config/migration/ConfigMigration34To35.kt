package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration34To35 : ConfigMigration(
    34,
    35,
    "Added managed-servers.accept-agent-links"
) {
    override fun migrate(config: JsonObject) {
        // Into the existing block, keeping what it holds; a hand-removed block is written back
        // whole, like ConfigMigration33To34 does, because a missing block deserialises to null.
        val managedServers = config.getJsonObject("managed-servers")
            ?: JsonObject()
                .put("plugin-jar-dir", null as String?)
                .put("node-auto-update", true)
                .also { config.put("managed-servers", it) }

        if (!managedServers.containsKey("accept-agent-links")) {
            managedServers.put("accept-agent-links", true)
        }
    }
}
