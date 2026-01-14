package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration18To19 : ConfigMigration(18, 19, "Add HTTP -> HTTPS redirect setting.") {
    override fun migrate(config: JsonObject) {
        val server = config.getJsonObject("server") ?: JsonObject()

        if (!server.containsKey("redirect-https")) {
            server.put("redirect-https", false)
        }

        config.put("server", server)
    }
}
