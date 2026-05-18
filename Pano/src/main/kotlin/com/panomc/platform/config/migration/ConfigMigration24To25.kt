package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

/**
 * No-op migration whose only job is to bump the config version so the new commented HOCON
 * layout is written to existing installs on next startup. The actual reformat happens in
 * [com.panomc.platform.config.ConfigManager.saveConfig] via [com.panomc.platform.config.HoconWriter].
 */
@Migration
class ConfigMigration24To25 : ConfigMigration(
    24,
    25,
    "Re-render config.conf with inline documentation and section banners"
) {
    override fun migrate(config: JsonObject) {
        // intentionally empty
    }
}
