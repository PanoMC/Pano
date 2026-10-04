package com.panomc.platform.server.plugins

import com.panomc.platform.config.migration.ConfigMigration32To33
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigMigration32To33Test {
    @Test
    fun `writes the plugin-sources block explicitly`() {
        val config = JsonObject().put("config-version", 32)

        ConfigMigration32To33().migrate(config)

        val block = config.getJsonObject("plugin-sources")

        assertTrue(block.containsKey("curseforge-api-key"))
        assertEquals(null, block.getString("curseforge-api-key"))
    }

    @Test
    fun `migrates from thirty two to thirty three`() {
        val migration = ConfigMigration32To33()

        assertEquals(32, migration.from)
        assertEquals(33, migration.to)
        assertTrue(migration.isMigratable(32))
        assertFalse(migration.isMigratable(31))
    }
}
