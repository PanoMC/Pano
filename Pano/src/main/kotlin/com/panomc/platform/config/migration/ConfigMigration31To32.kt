package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration31To32 : ConfigMigration(
    31,
    32,
    "Added managed-servers"
) {
    override fun migrate(config: JsonObject) {
        // Written explicitly rather than left to the Kotlin default, for the same reason
        // ConfigMigration30To31 does it: PanoConfig has no no-arg constructor, so Gson allocates
        // it through Unsafe and a key missing from config.conf would deserialise the whole block
        // to null instead of to these defaults.
        config.put(
            "managed-servers",
            JsonObject()
                .put("plugin-jar-dir", null as String?)
        )
    }
}
