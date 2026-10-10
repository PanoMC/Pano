package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration40To41 : ConfigMigration(
    40,
    41,
    "Added email.provider (SMTP or PANO_MAIL)"
) {
    override fun migrate(config: JsonObject) {
        // Existing installs keep sending through SMTP.
        val email = config.getJsonObject("email") ?: return

        if (!email.containsKey("provider")) {
            email.put("provider", "SMTP")
        }
    }
}
