package com.panomc.platform.config

import com.panomc.platform.config.migration.ConfigMigration28To29
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ConfigMigration28To29]. The migration has to write every key of the new block
 * explicitly, because [PanoConfig] has no no-arg constructor and a key missing from config.conf
 * would deserialise to false instead of the Kotlin default.
 */
class ConfigMigration28To29Test {

    private val migration = ConfigMigration28To29()

    @Test
    fun `migrates only from version 28`() {
        assertTrue(migration.isMigratable(28))
        assertFalse(migration.isMigratable(27))
        assertFalse(migration.isMigratable(29))
        assertEquals(29, migration.to)
    }

    @Test
    fun `writes the telemetry block with reporting enabled`() {
        val config = JsonObject().put("config-version", 28)

        migration.migrate(config)

        val telemetry = config.getJsonObject("telemetry")

        assertTrue(telemetry != null, "telemetry block must be written")
        assertEquals(true, telemetry.getBoolean("enabled"))
    }

    @Test
    fun `leaves unrelated keys untouched`() {
        val config = JsonObject()
            .put("config-version", 28)
            .put("website-name", "Antik Küp")
            .put("mc-server-connection", JsonObject().put("heartbeat-interval-seconds", 25))

        migration.migrate(config)

        assertEquals("Antik Küp", config.getString("website-name"))
        assertEquals(25, config.getJsonObject("mc-server-connection").getInteger("heartbeat-interval-seconds"))
    }
}
