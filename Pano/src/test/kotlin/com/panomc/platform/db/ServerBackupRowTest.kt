package com.panomc.platform.db

import com.google.gson.GsonBuilder
import com.panomc.platform.db.migration.DatabaseMigration50to51
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerBackup
import com.panomc.platform.server.backup.BackupMode
import com.panomc.platform.server.backup.BackupScope
import com.panomc.platform.util.deserializer.BooleanDeserializer
import com.panomc.platform.util.deserializer.LenientListStringAdapterFactory
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The backups v2 columns as they come back from MariaDB: a `tinyint` for `pinned`, JSON text (or
 * NULL) for the two lists, plain strings for the enums. Read with the same adapters the platform's
 * Gson registers, since that is what turns a row into a [ServerBackup].
 */
class ServerBackupRowTest {
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(LenientListStringAdapterFactory())
        .registerTypeAdapter(Boolean::class.java, BooleanDeserializer())
        .registerTypeAdapter(java.lang.Boolean::class.java, BooleanDeserializer())
        .create()

    private fun row(extra: JsonObject = JsonObject()) = JsonObject()
        .put("id", 7)
        .put("uuid", "b-7")
        .put("serverId", 3)
        .putNull("nodeId")
        .put("name", "nightly")
        .put("sizeBytes", 1024)
        .putNull("sha256")
        .put("status", "READY")
        .put("createdBy", -1)
        .put("createdAt", 1000)
        .mergeIn(extra)

    @Test
    fun `reads the v2 columns`() {
        val backup = gson.fromJson(
            row(
                JsonObject()
                    .put("mode", "SNAPSHOT")
                    .put("scope", "CUSTOM")
                    .put("pinned", 1)
                    .put("fileCount", 42)
                    .put("storedBytes", 512)
                    .put("include", "[\"world/\"]")
                    .put("exclude", "[\"logs/\",\"cache/\"]")
            ).encode(),
            ServerBackup::class.java
        )

        assertEquals(BackupMode.SNAPSHOT, backup.mode)
        assertEquals(BackupScope.CUSTOM, backup.scope)
        assertTrue(backup.pinned)
        assertEquals(42L, backup.fileCount)
        assertEquals(512L, backup.storedBytes)
        assertEquals(listOf("world/"), backup.include)
        assertEquals(listOf("logs/", "cache/"), backup.exclude)
    }

    @Test
    fun `reads a migrated pre-v2 row as a full, unpinned backup of everything`() {
        val backup = gson.fromJson(
            row(
                JsonObject()
                    .put("mode", "FULL")
                    .put("scope", "ALL")
                    .put("pinned", 0)
                    .putNull("fileCount")
                    .putNull("storedBytes")
                    .putNull("include")
                    .putNull("exclude")
            ).encode(),
            ServerBackup::class.java
        )

        assertEquals(BackupMode.FULL, backup.mode)
        assertEquals(BackupScope.ALL, backup.scope)
        assertFalse(backup.pinned)
        assertNull(backup.fileCount)
        assertNull(backup.storedBytes)
        assertTrue(backup.include.isEmpty())
        assertTrue(backup.exclude.isEmpty())
    }

    @Test
    fun `hands the panel the v2 fields`() {
        val json = ServerBackup(
            uuid = "b-1",
            serverId = 1,
            name = "x",
            createdBy = 1,
            mode = BackupMode.SNAPSHOT,
            scope = BackupScope.WORLDS,
            pinned = true,
            fileCount = 3,
            storedBytes = 9,
            exclude = listOf("logs/")
        ).toPublicJsonObject()

        assertEquals("SNAPSHOT", json.getString("mode"))
        assertEquals("WORLDS", json.getString("scope"))
        assertEquals(true, json.getBoolean("pinned"))
        assertEquals(3L, json.getLong("fileCount"))
        assertEquals(9L, json.getLong("storedBytes"))
        assertEquals(listOf("logs/"), json.getJsonArray("exclude").list)
        assertTrue(json.getJsonArray("include").isEmpty)
    }

    @Test
    fun `reads settings written before snapshots with the snapshot defaults`() {
        val settings = JsonObject().put("backupKeepLast", 5).mapTo(Server.Companion.ServerSettings::class.java)

        assertEquals(5, settings.backupKeepLast)
        assertEquals(Server.Companion.ServerSettings.DEFAULT_SNAPSHOT_KEEP_LAST, settings.snapshotKeepLast)
        assertNull(settings.snapshotMaxBytes)

        val roundTrip = JsonObject(settings.apply { snapshotMaxBytes = 1L shl 40 }.encode())
            .mapTo(Server.Companion.ServerSettings::class.java)

        assertEquals(1L shl 40, roundTrip.snapshotMaxBytes)
    }

    @Test
    fun `migrates 50 to 51 with one handler per new column`() {
        val migration = DatabaseMigration50to51()

        assertEquals(50, migration.from)
        assertEquals(51, migration.to)
        assertEquals(7, migration.handlers.size)
        assertTrue(migration.isMigratable(50))
    }
}
