package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration19To20 : ConfigMigration(19, 20, "Add database type setting.") {
    override fun migrate(config: JsonObject) {
        val database = config.getJsonObject("database") ?: JsonObject()

        if (!database.containsKey("type")) {
            database.put("type", "mariadb")
        }

        config.put("database", database)
    }
}
