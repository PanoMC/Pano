package com.panomc.platform.server.schedule

import com.panomc.platform.error.InvalidData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleDefinitionsTest {
    private fun task(kind: String, payload: JsonObject = JsonObject()): JsonObject =
        JsonObject().put("kind", kind).put("payload", payload)

    private fun body(vararg tasks: JsonObject): JsonObject = JsonObject()
        .put("name", "Nightly restart")
        .put("cron", "0 4 * * *")
        .put("timezone", "Europe/Istanbul")
        .put("enabled", true)
        .put("warnMinutes", 15)
        .put("tasks", JsonArray(tasks.toList()))

    private fun parse(body: JsonObject, backupsAllowed: Boolean = true) =
        ScheduleDefinitions.parse(body, backupsAllowed, "UTC")

    @Test
    fun `keeps the order the tasks were sent in`() {
        val definition = parse(
            body(
                task("BACKUP", JsonObject().put("name", "nightly")),
                task("COMMAND", JsonObject().put("command", "save-all")),
                task("POWER", JsonObject().put("action", "RESTART"))
            )
        )

        assertEquals(
            listOf(ScheduleTaskKind.BACKUP, ScheduleTaskKind.COMMAND, ScheduleTaskKind.POWER),
            definition.tasks.map { it.kind }
        )
        assertEquals("save-all", definition.tasks[1].payload.getString("command"))
        assertEquals("RESTART", definition.tasks[2].payload.getString("action"))
    }

    @Test
    fun `refuses a power action a schedule may not perform`() {
        listOf("START", "KILL", "nonsense").forEach { action ->
            assertThrows(InvalidData::class.java) {
                parse(body(task("POWER", JsonObject().put("action", action))))
            }
        }
    }

    @Test
    fun `refuses a backup on a server with no files`() {
        assertThrows(InvalidData::class.java) {
            parse(body(task("BACKUP")), backupsAllowed = false)
        }
    }

    @Test
    fun `keeps a per-schedule retention override`() {
        val definition = parse(body(task("BACKUP", JsonObject().put("name", "weekly").put("keep", 4))))

        assertEquals(4, definition.tasks.single().payload.getInteger("keep"))
    }

    @Test
    fun `refuses an unparsable cron, an unknown zone and an empty name`() {
        assertThrows(InvalidData::class.java) {
            parse(body(task("COMMAND", JsonObject().put("command", "list"))).put("cron", "0 4 * *"))
        }

        assertThrows(InvalidData::class.java) {
            parse(body(task("COMMAND", JsonObject().put("command", "list"))).put("timezone", "Mars/Olympus"))
        }

        assertThrows(InvalidData::class.java) {
            parse(body(task("COMMAND", JsonObject().put("command", "list"))).put("name", "   "))
        }
    }

    @Test
    fun `refuses an empty command and an empty task list`() {
        assertThrows(InvalidData::class.java) {
            parse(body(task("COMMAND", JsonObject().put("command", "  "))))
        }

        assertThrows(InvalidData::class.java) {
            parse(body())
        }
    }

    @Test
    fun `falls back to the platform zone and clamps the countdown`() {
        val definition = ScheduleDefinitions.parse(
            body(task("COMMAND", JsonObject().put("command", "list")))
                .put("timezone", "")
                .put("warnMinutes", 10_000),
            backupsAllowed = true,
            defaultTimezone = "UTC"
        )

        assertEquals("UTC", definition.timezone)
        assertEquals(ScheduleWarnings.MAX_WARN_MINUTES, definition.warnMinutes)
        assertTrue(definition.enabled)
    }

    @Test
    fun `keeps a backup step's mode, scope and lists as sent`() {
        val definition = parse(
            body(
                task(
                    "BACKUP",
                    JsonObject()
                        .put("name", "hourly")
                        .put("keep", 24)
                        .put("mode", "SNAPSHOT")
                        .put("scope", "CUSTOM")
                        .put("include", JsonArray().add("world/").add("plugins/Essentials/"))
                        .put("exclude", JsonArray().add("world/datapacks/"))
                        .put("excludeDefaults", false)
                )
            )
        )

        val payload = definition.tasks.single().payload

        assertEquals("hourly", payload.getString("name"))
        assertEquals(24, payload.getInteger("keep"))
        assertEquals("SNAPSHOT", payload.getString("mode"))
        assertEquals("CUSTOM", payload.getString("scope"))
        assertEquals(JsonArray().add("world/").add("plugins/Essentials/"), payload.getJsonArray("include"))
        // Stored as the extras the operator typed; the defaults are only added on the way out.
        assertEquals(JsonArray().add("world/datapacks/"), payload.getJsonArray("exclude"))
        assertEquals(false, payload.getBoolean("excludeDefaults"))
    }

    @Test
    fun `defaults an old-style backup step to a full zip of everything`() {
        val payload = parse(body(task("BACKUP", JsonObject().put("name", "nightly")))).tasks.single().payload

        assertEquals("FULL", payload.getString("mode"))
        assertEquals("ALL", payload.getString("scope"))
        assertTrue(payload.getJsonArray("include").isEmpty)
        assertTrue(payload.getJsonArray("exclude").isEmpty)
        assertEquals(true, payload.getBoolean("excludeDefaults"))
    }

    @Test
    fun `refuses a backup step with bad options`() {
        val bad = listOf(
            JsonObject().put("mode", "DIFFERENTIAL"),
            JsonObject().put("scope", "EVERYTHING"),
            JsonObject().put("scope", "CUSTOM"),
            JsonObject().put("scope", "CUSTOM").put("include", JsonArray()),
            JsonObject().put("scope", "CUSTOM").put("include", JsonArray().add("../escape")),
            JsonObject().put("exclude", JsonArray().add("/absolute")),
            JsonObject().put("exclude", JsonArray().add("x".repeat(256))),
            JsonObject().put("exclude", JsonArray((1..51).map { "d$it/" })),
            JsonObject().put("exclude", "logs/"),
            JsonObject().put("excludeDefaults", "yes")
        )

        bad.forEach { payload ->
            assertThrows(InvalidData::class.java, { parse(body(task("BACKUP", payload))) }, payload.encode())
        }
    }

    @Test
    fun `relays a backup step with the full exclude list`() {
        val stored = parse(
            body(
                task(
                    "BACKUP",
                    JsonObject().put("mode", "SNAPSHOT").put("scope", "WORLDS").put("exclude", JsonArray().add("world/datapacks/"))
                )
            )
        ).tasks.single().payload

        val relayed = ScheduleDefinitions.relayPayload(ScheduleTaskKind.BACKUP, stored)

        assertEquals(
            JsonArray().add("logs/").add("cache/").add("*.jar.tmp").add("world/datapacks/"),
            relayed.getJsonArray("exclude")
        )
        assertEquals("SNAPSHOT", relayed.getString("mode"))
        assertEquals("WORLDS", relayed.getString("scope"))
        assertTrue(relayed.getJsonArray("include").isEmpty)
        assertTrue(!relayed.containsKey("excludeDefaults"))

        // The stored payload itself is left alone.
        assertEquals(JsonArray().add("world/datapacks/"), stored.getJsonArray("exclude"))
    }

    @Test
    fun `relays excludeDefaults false as just the extras, and a pre-v2 step with the defaults`() {
        val withoutDefaults = ScheduleDefinitions.relayPayload(
            ScheduleTaskKind.BACKUP,
            JsonObject().put("exclude", JsonArray().add("*.log")).put("excludeDefaults", false)
        )

        assertEquals(JsonArray().add("*.log"), withoutDefaults.getJsonArray("exclude"))

        val legacy = ScheduleDefinitions.relayPayload(
            ScheduleTaskKind.BACKUP,
            JsonObject().put("name", "nightly").put("keep", 7)
        )

        assertEquals("FULL", legacy.getString("mode"))
        assertEquals("ALL", legacy.getString("scope"))
        assertEquals(JsonArray().add("logs/").add("cache/").add("*.jar.tmp"), legacy.getJsonArray("exclude"))
        assertEquals("nightly", legacy.getString("name"))
        assertEquals(7, legacy.getInteger("keep"))
    }

    @Test
    fun `relays other steps untouched`() {
        val payload = JsonObject().put("command", "save-all")

        assertEquals(payload, ScheduleDefinitions.relayPayload(ScheduleTaskKind.COMMAND, payload))
    }
}
