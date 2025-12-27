package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration16To17 : ConfigMigration(16, 17, "Add release-channel option (alpha/beta/stable)") {
    override fun migrate(config: JsonObject) {
        // Stored as ReleaseStage enum name ("ALPHA" | "BETA" | "RELEASE")
        if (config.getString("release-channel") == null) {
            config.put("release-channel", "ALPHA")
        }
    }
}


