package com.panomc.platform.node

import com.panomc.platform.config.migration.ConfigMigration31To32
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigMigration31To32Test {
    @Test
    fun `writes the managed-servers block explicitly`() {
        val config = JsonObject().put("config-version", 31)

        ConfigMigration31To32().migrate(config)

        val block = config.getJsonObject("managed-servers")

        assertTrue(block.containsKey("plugin-jar-dir"))
        assertEquals(null, block.getString("plugin-jar-dir"))
    }

    @Test
    fun `migrates from thirty one to thirty two`() {
        val migration = ConfigMigration31To32()

        assertEquals(31, migration.from)
        assertEquals(32, migration.to)
        assertTrue(migration.isMigratable(31))
        assertFalse(migration.isMigratable(30))
    }
}
