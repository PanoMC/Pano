package com.panomc.platform.backup.remote

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId

/**
 * Pano Backup (remote) settings: [schedule] of the automatic upload (at the local [hour]; the plan has
 * no frequency rule), [mcServerIds] = managed MC servers whose finished full backups are also
 * uploaded (`kind: mc-server`).
 */
data class RemoteBackupSettings(
    val schedule: Schedule = Schedule.OFF,
    val hour: Int = 3,
    val mcServerIds: List<Long> = emptyList()
) {
    enum class Schedule(val intervalMs: Long) {
        OFF(0),
        DAILY(24L * 60 * 60 * 1000),
        WEEKLY(7L * 24 * 60 * 60 * 1000)
    }

    fun toJson(): JsonObject = JsonObject()
        .put("schedule", schedule.name)
        .put("hour", hour)
        .put("mcServerIds", JsonArray(mcServerIds))

    /** Whether an upload is due at [now]: schedule on, local [hour] reached, the last upload a schedule ago (minus slack for tick jitter). */
    fun isDue(lastUploadAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (schedule == Schedule.OFF || Instant.ofEpochMilli(now).atZone(zone).hour < hour) {
            return false
        }

        if (lastUploadAt == null) {
            return true
        }

        return now - lastUploadAt >= schedule.intervalMs - minOf(SLACK_MS, schedule.intervalMs / 4)
    }

    companion object {
        const val MAX_MC_SERVERS = 100
        private const val SLACK_MS = 60L * 60 * 1000

        /** `TIER` (the link era's "as often as the tier allows") reads as `DAILY`. */
        fun fromJson(json: JsonObject?): RemoteBackupSettings? {
            json ?: return null

            val raw = json.getValue("schedule")
            val schedule = if (raw == "TIER") Schedule.DAILY else Schedule.values().firstOrNull { it.name == raw } ?: return null
            val hour = (json.getValue("hour") as? Number)?.toInt() ?: return null
            val servers = json.getValue("mcServerIds") ?: JsonArray()

            if (hour !in 0..23 || servers !is JsonArray || servers.size() > MAX_MC_SERVERS) {
                return null
            }

            val ids = servers.map { (it as? Number)?.toLong() ?: return null }.distinct()

            return RemoteBackupSettings(schedule, hour, ids)
        }
    }
}

/**
 * Everything persisted about Pano Backup on this Pano (the link era's `links` key is ignored when
 * read and dropped on the next save: the platform connection is the credential now).
 */
data class RemoteBackupState(
    val settings: RemoteBackupSettings = RemoteBackupSettings(),
    val lastUploadAt: Long? = null
) {
    fun toJson(): JsonObject = JsonObject()
        .put("settings", settings.toJson())
        .put("lastUploadAt", lastUploadAt)

    companion object {
        const val OPTION = "pano_backup_remote"

        /** The system_property holding this Pano's `X-Pano-Instance-Id` (generated once, survives reconnects). */
        const val INSTANCE_ID_OPTION = "pano-backup-instance-id"

        fun parse(value: String?): RemoteBackupState = try {
            val json = value?.let { JsonObject(it) } ?: JsonObject()

            RemoteBackupState(
                settings = RemoteBackupSettings.fromJson(json.getJsonObject("settings")) ?: RemoteBackupSettings(),
                lastUploadAt = json.getLong("lastUploadAt")
            )
        } catch (_: Exception) {
            RemoteBackupState()
        }
    }
}

/** Where [RemoteBackupState] lives: the database for a running Pano, memory in setup mode and tests. */
interface RemoteStateStore {
    suspend fun load(): RemoteBackupState
    suspend fun save(state: RemoteBackupState)
}

class MemoryRemoteStateStore(@Volatile var state: RemoteBackupState = RemoteBackupState()) : RemoteStateStore {
    override suspend fun load() = state

    override suspend fun save(state: RemoteBackupState) {
        this.state = state
    }
}

/**
 * The Pano Backup passphrase, kept on this server only so scheduled uploads can run: a file readable
 * by the Pano user alone, in the local backups folder, which no archive includes (so it is never
 * inside a backup and never in the database). Lost passphrase = the remote backups are unrecoverable.
 */
class PassphraseFile(private val file: File) {
    fun isSet(): Boolean = file.isFile && file.length() > 0

    fun read(): CharArray? = if (isSet()) file.readText(Charsets.UTF_8).toCharArray().takeIf { it.isNotEmpty() } else null

    fun write(passphrase: CharArray?) {
        if (passphrase == null || passphrase.isEmpty()) {
            file.delete()

            return
        }

        file.parentFile?.mkdirs()

        val part = File(file.parentFile, file.name + ".tmp")

        part.delete()
        part.createNewFile()
        restrict(part)
        part.writeText(String(passphrase), Charsets.UTF_8)

        Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun restrict(target: File) {
        try {
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            target.setReadable(false, false)
            target.setReadable(true, true)
            target.setWritable(false, false)
            target.setWritable(true, true)
        }
    }

    companion object {
        const val FILE_NAME = ".pano-backup-passphrase"
        const val MIN_LENGTH = 8
    }
}
