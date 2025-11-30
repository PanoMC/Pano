package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import com.panomc.platform.util.HashUtil.hash
import io.vertx.core.json.JsonObject
import java.io.File

@Migration
class ConfigMigration15To16 : ConfigMigration(15, 16, "Add saving hash of website logo and favicon") {
    override fun migrate(config: JsonObject) {
        val filePaths = config.getJsonObject("file-paths")
        val newFilePaths = mutableMapOf<String, Map<String, String>>()

        val websiteLogoFile =
            File(config.getString("file-uploads-folder") + File.separator + filePaths.getString("websiteLogo"))

        if (websiteLogoFile.exists()) {
            val hash = websiteLogoFile.inputStream().hash()

            newFilePaths["websiteLogo"] = mapOf(
                "path" to filePaths.getString("websiteLogo"),
                "hash" to hash
            )
        }

        val favicon =
            File(config.getString("file-uploads-folder") + File.separator + filePaths.getString("favicon"))

        if (favicon.exists()) {
            val hash = favicon.inputStream().hash()

            newFilePaths["favicon"] = mapOf(
                "path" to filePaths.getString("favicon"),
                "hash" to hash
            )
        }

        config.put("file-paths", newFilePaths)
    }
}