package com.panomc.platform.node

import com.panomc.platform.config.migration.ConfigMigration30To31
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigMigration30To31Test {
    @Test
    fun `writes the local-node block explicitly`() {
        val config = JsonObject().put("config-version", 30)

        ConfigMigration30To31().migrate(config)

        val block = config.getJsonObject("local-node")

        assertTrue(block.getBoolean("enabled"))
        assertFalse(block.getBoolean("stop-with-pano"))
        assertTrue(block.containsKey("jar-path"))
        assertEquals(null, block.getString("jar-path"))
        assertTrue(block.containsKey("java-path"))
        assertEquals(null, block.getString("java-path"))
    }

    @Test
    fun `migrates from thirty to thirty one`() {
        val migration = ConfigMigration30To31()

        assertEquals(30, migration.from)
        assertEquals(31, migration.to)
        assertTrue(migration.isMigratable(30))
        assertFalse(migration.isMigratable(29))
    }
}
