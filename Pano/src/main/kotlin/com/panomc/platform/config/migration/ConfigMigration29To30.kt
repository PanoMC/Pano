package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration29To30 : ConfigMigration(
    29,
    30,
    "Added usage-mode"
) {
    override fun migrate(config: JsonObject) {
        // Written explicitly: PanoConfig has no no-arg constructor, so Gson allocates it via
        // Unsafe and a key missing from config.conf would deserialise to null instead of the
        // Kotlin default. Existing installs keep today's behaviour, which is BOTH.
        config.put("usage-mode", "BOTH")
    }
}
