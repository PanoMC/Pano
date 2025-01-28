package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration1To2 : ConfigMigration(1, 2, "Convert access_token to access-token") {
    override fun migrate(config: JsonObject) {
        val panoAccountJsonObject = config.getJsonObject("pano-account")

        panoAccountJsonObject.put("access-token", panoAccountJsonObject.getString("access_token"))
        panoAccountJsonObject.remove("access_token")
    }
}