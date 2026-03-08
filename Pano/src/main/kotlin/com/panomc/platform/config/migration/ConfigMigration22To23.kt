package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration22To23 : ConfigMigration(
  22,
  23,
  "Added password hash algorithm setting to auth config"
) {
    override fun migrate(config: JsonObject) {
        val authConfig = config.getJsonObject("auth") ?: JsonObject()

        authConfig.put("password-hash-algorithm", "ARGON2ID")

        config.put("auth", authConfig)
    }
}
