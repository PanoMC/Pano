package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration30To31 : ConfigMigration(
    30,
    31,
    "Added local-node"
) {
    override fun migrate(config: JsonObject) {
        // Written explicitly rather than left to the Kotlin default: PanoConfig has no no-arg
        // constructor, so Gson allocates it through Unsafe and a key missing from config.conf
        // would deserialise the whole block to null instead. Existing installs get the defaults,
        // which change nothing until an admin sets a local node up from the panel.
        config.put(
            "local-node",
            JsonObject()
                .put("enabled", true)
                .put("jar-path", null as String?)
                .put("java-path", null as String?)
                .put("stop-with-pano", false)
        )
    }
}
