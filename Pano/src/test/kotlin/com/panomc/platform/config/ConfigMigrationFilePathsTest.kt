package com.panomc.platform.config

import com.google.gson.Gson
import com.panomc.platform.config.migration.ConfigMigration15To16
import com.panomc.platform.config.migration.ConfigMigration41To42
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The logo and the favicon survive the file-paths migrations under the keys PanoConfig.FilePaths reads. */
class ConfigMigrationFilePathsTest {
    @TempDir
    lateinit var temp: File

    private fun filePathsOf(config: JsonObject): PanoConfig.Companion.FilePaths =
        Gson().fromJson(config.getJsonObject("file-paths").encode(), PanoConfig.Companion.FilePaths::class.java)

    @Test
    fun `15 to 16 then 41 to 42 keeps logo and favicon readable`() {
        File(temp, "logo.png").writeText("logo")
        File(temp, "favicon.ico").writeText("icon")

        val config = JsonObject()
            .put("file-uploads-folder", temp.path)
            .put("file-paths", JsonObject().put("websiteLogo", "logo.png").put("favicon", "favicon.ico"))

        ConfigMigration15To16().migrate(config)
        ConfigMigration41To42().migrate(config)

        val filePaths = filePathsOf(JsonObject(config.encode()))

        assertEquals("logo.png", filePaths.websiteLogoFile?.path)
        assertEquals("favicon.ico", filePaths.faviconFile?.path)
    }

    @Test
    fun `41 to 42 moves the keys an older 15 to 16 wrote`() {
        val config = JsonObject().put(
            "file-paths", JsonObject()
                .put("websiteLogo", JsonObject().put("path", "logo.png").put("hash", "a"))
                .put("favicon", JsonObject().put("path", "favicon.ico").put("hash", "b"))
        )

        ConfigMigration41To42().migrate(config)

        val filePaths = config.getJsonObject("file-paths")

        assertEquals("logo.png", filePathsOf(config).websiteLogoFile?.path)
        assertEquals("favicon.ico", filePathsOf(config).faviconFile?.path)
        assertFalse(filePaths.containsKey("websiteLogo"))
        assertFalse(filePaths.containsKey("favicon"))
    }

    @Test
    fun `41 to 42 keeps a value that is already under the new key`() {
        val config = JsonObject().put(
            "file-paths", JsonObject()
                .put("websiteLogo", JsonObject().put("path", "old.png").put("hash", "a"))
                .put("websiteLogoFile", JsonObject().put("path", "new.png").put("hash", "b"))
        )

        ConfigMigration41To42().migrate(config)

        assertEquals("new.png", filePathsOf(config).websiteLogoFile?.path)
    }
}
