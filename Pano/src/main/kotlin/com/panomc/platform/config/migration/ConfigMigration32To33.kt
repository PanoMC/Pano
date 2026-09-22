package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration32To33 : ConfigMigration(
    32,
    33,
    "Added plugin-sources"
) {
    override fun migrate(config: JsonObject) {
        // Written explicitly, like ConfigMigration31To32: PanoConfig has no no-arg constructor, so
        // Gson allocates it through Unsafe and a block missing from config.conf deserialises to
        // null rather than to the Kotlin defaults.
        config.put(
            "plugin-sources",
            JsonObject()
                .put("curseforge-api-key", null as String?)
        )
    }
}
