package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration35To36 : ConfigMigration(
    35,
    36,
    "Added email.host-managed"
) {
    override fun migrate(config: JsonObject) {
        // Every existing mail block stays on Pano Host mail (what it followed so far); self-hosted Panos ignore it.
        val email = config.getJsonObject("email") ?: return

        if (!email.containsKey("host-managed")) email.put("host-managed", true)
    }
}
