package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

/**
 * ConfigMigration15To16 stored the logo and the favicon under `file-paths.websiteLogo` / `favicon`, keys Pano
 * never reads (it reads `websiteLogoFile` / `faviconFile`), so an install upgraded through it lost both. They are
 * moved to the keys that are read, unless that key already has a value.
 */
@Migration
class ConfigMigration41To42 : ConfigMigration(
    41,
    42,
    "Moved file-paths.websiteLogo / favicon to websiteLogoFile / faviconFile"
) {
    override fun migrate(config: JsonObject) {
        val filePaths = config.getValue("file-paths") as? JsonObject ?: return

        RENAMES.forEach { (old, new) ->
            val value = filePaths.getValue(old)

            if (value is JsonObject && filePaths.getValue(new) == null) {
                filePaths.put(new, value)
            }

            if (value is JsonObject) {
                filePaths.remove(old)
            }
        }
    }

    companion object {
        val RENAMES = listOf("websiteLogo" to "websiteLogoFile", "favicon" to "faviconFile")
    }
}
