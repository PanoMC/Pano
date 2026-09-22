package com.panomc.platform.server.backup

import com.panomc.platform.error.InvalidData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupOptionsTest {
    private fun field(block: () -> Unit): Any? =
        JsonObject(assertThrows(InvalidData::class.java) { block() }.encode(emptyMap())).getValue("field")

    @Test
    fun `absent means a full zip of everything with the default excludes`() {
        listOf(null, JsonObject()).forEach { body ->
            val options = BackupOptions.parse(body)

            assertEquals(BackupMode.FULL, options.mode)
            assertEquals(BackupScope.ALL, options.scope)
            assertTrue(options.include.isEmpty())
            assertEquals(listOf("logs/", "cache/", "*.jar.tmp"), options.effectiveExclude())
        }
    }

    @Test
    fun `reads mode and scope case-insensitively`() {
        val options = BackupOptions.parse(JsonObject().put("mode", "snapshot").put("scope", " Worlds "))

        assertEquals(BackupMode.SNAPSHOT, options.mode)
        assertEquals(BackupScope.WORLDS, options.scope)
    }

    @Test
    fun `refuses an unknown or mistyped mode and scope`() {
        assertEquals("mode", field { BackupOptions.parse(JsonObject().put("mode", "INCREMENTAL")) })
        assertEquals("mode", field { BackupOptions.parse(JsonObject().put("mode", 1)) })
        assertEquals("scope", field { BackupOptions.parse(JsonObject().put("scope", "PLUGINS")) })
    }

    @Test
    fun `requires a non-empty include for CUSTOM`() {
        assertEquals("include", field { BackupOptions.parse(JsonObject().put("scope", "CUSTOM")) })
        assertEquals("include", field {
            BackupOptions.parse(JsonObject().put("scope", "CUSTOM").put("include", JsonArray()))
        })

        val options = BackupOptions.parse(
            JsonObject().put("scope", "CUSTOM").put("include", JsonArray().add("world/").add("plugins/Essentials"))
        )

        assertEquals(listOf("world/", "plugins/Essentials"), options.include)
    }

    @Test
    fun `drops an include list for the scopes that do not use one`() {
        val options = BackupOptions.parse(JsonObject().put("scope", "WORLDS").put("include", JsonArray().add("plugins/")))

        assertTrue(options.include.isEmpty())
    }

    @Test
    fun `prepends the defaults to the extras unless told not to`() {
        val extras = JsonArray().add("world/datapacks/").add("logs/")

        assertEquals(
            listOf("logs/", "cache/", "*.jar.tmp", "world/datapacks/"),
            BackupOptions.parse(JsonObject().put("exclude", extras)).effectiveExclude()
        )

        assertEquals(
            listOf("world/datapacks/", "logs/"),
            BackupOptions.parse(JsonObject().put("exclude", extras).put("excludeDefaults", false)).effectiveExclude()
        )

        assertTrue(BackupOptions.parse(JsonObject().put("excludeDefaults", false)).effectiveExclude().isEmpty())
    }

    @Test
    fun `refuses an excludeDefaults that is not a boolean`() {
        assertEquals("excludeDefaults", field { BackupOptions.parse(JsonObject().put("excludeDefaults", "no")) })
    }

    @Test
    fun `refuses unsafe entries in either list`() {
        val unsafe = listOf(
            "", "   ", "/etc/passwd", "../other", "world/../../x", "C:/Windows", "a\\b", "a\u0000b", "./", ".",
            "x".repeat(BackupOptions.MAX_ENTRY_LENGTH + 1)
        )

        unsafe.forEach { entry ->
            assertEquals("exclude", field { BackupOptions.parse(JsonObject().put("exclude", JsonArray().add(entry))) }, entry)
            assertEquals("include", field {
                BackupOptions.parse(JsonObject().put("scope", "CUSTOM").put("include", JsonArray().add(entry)))
            }, entry)
        }
    }

    @Test
    fun `keeps the matcher's own syntax`() {
        listOf("logs/", "*.log", "world/region/r.0.0.mca", "plugins/dynmap/web/tiles/", "a b/c")
            .forEach { assertTrue(BackupOptions.isSafeEntry(it), it) }

        assertEquals(
            listOf("*.log"),
            BackupOptions.parse(JsonObject().put("exclude", JsonArray().add(" *.log "))).exclude
        )
    }

    @Test
    fun `refuses a list that is too long, not a list, or has a non-string entry`() {
        val tooMany = JsonArray((1..BackupOptions.MAX_ENTRIES + 1).map { "dir$it/" })

        assertEquals("exclude", field { BackupOptions.parse(JsonObject().put("exclude", tooMany)) })
        assertEquals("exclude", field { BackupOptions.parse(JsonObject().put("exclude", "logs/")) })
        assertEquals("exclude", field { BackupOptions.parse(JsonObject().put("exclude", JsonArray().add(5))) })

        val exactlyEnough = JsonArray((1..BackupOptions.MAX_ENTRIES).map { "dir$it/" })

        assertEquals(BackupOptions.MAX_ENTRIES, BackupOptions.parse(JsonObject().put("exclude", exactlyEnough)).exclude.size)
    }

    @Test
    fun `stores what the operator sent, not the expanded list`() {
        val payload = BackupOptions.parse(
            JsonObject().put("mode", "SNAPSHOT").put("exclude", JsonArray().add("world/datapacks/"))
        ).toPayload()

        assertEquals("SNAPSHOT", payload.getString("mode"))
        assertEquals("ALL", payload.getString("scope"))
        assertEquals(JsonArray().add("world/datapacks/"), payload.getJsonArray("exclude"))
        assertTrue(payload.getBoolean("excludeDefaults"))

        // And it reads back to the same options.
        assertEquals(BackupOptions.parse(payload), BackupOptions.parse(BackupOptions.parse(payload).toPayload()))
        assertFalse(BackupOptions.parse(payload.copy().put("excludeDefaults", false)).excludeDefaults)
    }
}
