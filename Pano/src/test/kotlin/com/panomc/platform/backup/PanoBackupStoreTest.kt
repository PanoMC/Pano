package com.panomc.platform.backup

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

class PanoBackupStoreTest {
    @TempDir
    lateinit var temp: File

    private fun put(store: PanoBackupStore, tag: PanoBackupTag, createdAt: Long): PanoBackupInfo {
        val id = store.newId(createdAt)

        store.partFile(id).writeText("archive $id")

        return PanoBackupInfo(id, createdAt, 10, false, tag, "1.0.0", 1, 2).also { store.commit(it) }
    }

    @Test
    fun `commit makes a backup visible, list is newest first, delete removes both files`() {
        val store = PanoBackupStore(File(temp, "backups"))
        val old = put(store, PanoBackupTag.MANUAL, 1_000_000)
        val new = put(store, PanoBackupTag.SCHEDULED, 2_000_000)

        assertEquals(listOf(new.id, old.id), store.list().map { it.id })
        assertEquals(new, store.get(new.id))
        assertEquals("archive ${old.id}", store.archiveFile(old.id).readText())
        assertFalse(File(store.directory, "${old.id}.panoarc.part").exists())

        assertTrue(store.delete(old.id))
        assertFalse(store.delete(old.id))
        assertEquals(listOf(new.id), store.list().map { it.id })
        assertEquals(listOf(new.id + ".json", new.id + ".panoarc"), store.directory.list()!!.sorted())
    }

    @Test
    fun `ids are validated so no path ever leaves the folder`() {
        val store = PanoBackupStore(File(temp, "backups"))

        assertTrue(PanoBackupStore.isValidId(store.newId()))
        listOf("../x", "20260926-120000-abc", "20260926-120000-ABCDEF", "20260926-120000-abcdef/..", "", null).forEach {
            assertFalse(PanoBackupStore.isValidId(it), "$it")
        }

        assertNull(store.get("../../etc/passwd"))
        assertFalse(store.delete("../../etc/passwd"))
        assertThrows<IllegalArgumentException> { store.archiveFile("../config.conf") }
    }

    @Test
    fun `half-written archives and bad sidecars are not listed and parts are cleaned`() {
        val store = PanoBackupStore(File(temp, "backups"))
        val ok = put(store, PanoBackupTag.MANUAL, 5_000)
        val orphan = store.newId(6_000)

        store.partFile(orphan).writeText("partial")
        File(store.directory, "$orphan.json").writeText(ok.copy(id = orphan).toJson().encode())
        File(store.directory, "20260101-000000-zzzzzz.json").writeText("{not json")
        File(store.directory, "20260101-000000-zzzzzz.panoarc").writeText("x")

        assertEquals(listOf(ok.id), store.list().map { it.id })

        store.cleanParts()

        assertFalse(store.partFile(orphan).exists())
    }

    @Test
    fun `prune keeps the newest scheduled backups, drops old pre-restore ones and never touches manual ones`() {
        val store = PanoBackupStore(File(temp, "backups"))
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val manual = put(store, PanoBackupTag.MANUAL, now - 50 * day)
        val scheduled = (1..5).map { put(store, PanoBackupTag.SCHEDULED, now - it * day) }
        val freshSafety = put(store, PanoBackupTag.PRE_RESTORE, now - day)
        val oldSafety = put(store, PanoBackupTag.PRE_RESTORE, now - 8 * day)

        val pruned = store.prune(keepScheduled = 2, now = now)

        assertEquals((scheduled.drop(2).map { it.id } + oldSafety.id).sorted(), pruned.sorted())
        assertEquals(
            (listOf(manual.id, freshSafety.id) + scheduled.take(2).map { it.id }).sorted(),
            store.list().map { it.id }.sorted()
        )
    }

    @Test
    fun `info json round trip tolerates unknown tags`() {
        val info = PanoBackupInfo("20260926-101010-abcdef", 1, 2, true, PanoBackupTag.PRE_RESTORE, "v", 3, 4, "admin")

        assertEquals(info, PanoBackupInfo.fromJson(info.toJson()))
        assertEquals(PanoBackupTag.MANUAL, PanoBackupInfo.fromJson(info.toJson().put("tag", "WHAT")).tag)
        assertEquals("pano-backup-20260926-101010-abcdef.panoarc", info.fileName)
    }

    @Test
    fun `settings validate input and schedule by local hour and interval`() {
        assertEquals(PanoBackupSettings(), PanoBackupSettings.parse(null))
        assertEquals(PanoBackupSettings(), PanoBackupSettings.parse("{broken"))
        assertNull(PanoBackupSettings.fromJson(JsonObject().put("schedule", "HOURLY").put("hour", 1).put("keep", 1)))
        assertNull(PanoBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 24).put("keep", 1)))
        assertNull(PanoBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 3).put("keep", 0)))
        assertNull(PanoBackupSettings.fromJson(JsonObject().put("schedule", "DAILY").put("hour", 3).put("keep", 51)))

        val daily = PanoBackupSettings(PanoBackupSettings.Schedule.DAILY, hour = 3, keep = 7)

        assertEquals(daily, PanoBackupSettings.parse(daily.toJson().encode()))

        fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 9, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

        assertFalse(PanoBackupSettings().isDue(null, at(26, 12), ZoneOffset.UTC))
        assertFalse(daily.isDue(null, at(26, 2), ZoneOffset.UTC))
        assertTrue(daily.isDue(null, at(26, 3), ZoneOffset.UTC))
        assertFalse(daily.isDue(at(26, 3, 5), at(26, 23), ZoneOffset.UTC))
        assertFalse(daily.isDue(at(26, 3, 5), at(27, 2, 50), ZoneOffset.UTC))
        assertTrue(daily.isDue(at(26, 3, 5), at(27, 3, 0), ZoneOffset.UTC))

        val weekly = daily.copy(schedule = PanoBackupSettings.Schedule.WEEKLY)

        assertFalse(weekly.isDue(at(20, 3), at(26, 4), ZoneOffset.UTC))
        assertTrue(weekly.isDue(at(19, 3), at(26, 3), ZoneOffset.UTC))
    }
}
