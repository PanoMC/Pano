package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration27To28 : ConfigMigration(
    27,
    28,
    "Added the Minecraft server connection heartbeat settings"
) {
    override fun migrate(config: JsonObject) {
        // Every key is written explicitly: PanoConfig has no no-arg constructor, so Gson
        // allocates it via Unsafe and a key missing from config.conf would deserialise to
        // null/false instead of the Kotlin default.
        val mcServerConnectionConfig = JsonObject()

        mcServerConnectionConfig.put("heartbeat-interval-seconds", 25)
        mcServerConnectionConfig.put("heartbeat-timeout-seconds", 75)

        config.put("mc-server-connection", mcServerConnectionConfig)
    }
}
