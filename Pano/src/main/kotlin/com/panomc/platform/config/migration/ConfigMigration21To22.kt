package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject
import org.springframework.stereotype.Component

@Migration
@Component
class ConfigMigration21To22 : ConfigMigration(
  21,
  22,
  "Added enforce email verification setting"
) {
    override fun migrate(config: JsonObject) {
        val authConfig = JsonObject()

        authConfig.put("require-email-verification", false)

        config.put("auth", authConfig)
    }
}
