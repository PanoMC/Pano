package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration36To37 : ConfigMigration(
    36,
    37,
    "Added email.host-sender and email.custom"
) {
    override fun migrate(config: JsonObject) {
        // A Pano Host instance on its own SMTP keeps a copy of it, so switching to Pano Host mail and
        // back loses nothing. Everyone else starts without one.
        val email = config.getJsonObject("email") ?: return

        if (email.getBoolean("host-managed", true) || email.containsKey("custom")) return

        email.put(
            "custom",
            JsonObject()
                .put("sender", email.getString("sender", ""))
                .put("hostname", email.getString("hostname", ""))
                .put("port", email.getInteger("port", 465))
                .put("username", email.getString("username", ""))
                .put("password", email.getString("password", ""))
                .put("ssl", email.getBoolean("ssl", true))
                .put("starttls", email.getString("starttls", ""))
                .put("authMethods", email.getString("authMethods", ""))
        )
    }
}
