package com.panomc.platform.backup.remote

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId

/** An active link to Pano Host (the `HostLinkToken` and what it is bound to). */
data class HostLinkState(
    val purpose: LinkPurpose,
    val token: String,
    val linkId: String?,
    val instanceName: String,
    val workloadId: String?,
    val linkedAt: Long
) {
    fun toJson(): JsonObject = JsonObject()
        .put("purpose", purpose.name)
        .put("token", token)
        .put("linkId", linkId)
        .put("instanceName", instanceName)
        .put("workloadId", workloadId)
        .put("linkedAt", linkedAt)

    /** What the UI sees: never the token. */
    fun toPublicJson(): JsonObject = toJson().apply { remove("token") }

    companion object {
        fun fromJson(json: JsonObject?): HostLinkState? {
            json ?: return null

            return HostLinkState(
                purpose = LinkPurpose.values().firstOrNull { it.name == json.getString("purpose") } ?: return null,
                token = json.getString("token")?.takeIf { it.isNotBlank() } ?: return null,
                linkId = json.getString("linkId"),
                instanceName = json.getString("instanceName", ""),
                workloadId = json.getString("workloadId"),
                linkedAt = json.getLong("linkedAt", 0L)
            )
        }
    }
}

/** A device-code link waiting for approval on panomc.com (memory only: the poll token is short-lived). */
data class PendingLink(
    val purpose: LinkPurpose,
    val pollToken: String,
    val code: String,
    val verifyUrl: String,
    val instanceName: String,
    val expiresAt: Long,
    val intervalSeconds: Int
) {
    fun toPublicJson(): JsonObject = JsonObject()
        .put("purpose", purpose.name)
        .put("code", code)
        .put("verifyUrl", verifyUrl)
        .put("expiresAt", expiresAt)
        .put("interval", intervalSeconds)
}

/**
 * Pano Backup (remote) settings. [schedule] `TIER` = as often as the subscription tier allows; any
 * schedule is never more often than the tier's `minIntervalMinutes`. [mcServerIds] = managed MC
 * servers whose finished full backups are also uploaded (`kind: mc-server`).
 */
data class RemoteBackupSettings(
    val schedule: Schedule = Schedule.OFF,
    val hour: Int = 3,
    val mcServerIds: List<Long> = emptyList()
) {
    enum class Schedule(val intervalMs: Long) {
        OFF(0),
        TIER(0),
        DAILY(24L * 60 * 60 * 1000),
        WEEKLY(7L * 24 * 60 * 60 * 1000)
    }

    fun toJson(): JsonObject = JsonObject()
        .put("schedule", schedule.name)
        .put("hour", hour)
        .put("mcServerIds", JsonArray(mcServerIds))

    /**
     * Whether an upload is due at [now]: schedule on, local [hour] reached (not for `TIER`), and the
     * last upload at least max(schedule, tier minimum) ago (minus slack for tick jitter).
     */
    fun isDue(lastUploadAt: Long?, tierMinIntervalMinutes: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (schedule == Schedule.OFF) {
            return false
        }

        val tierMs = (tierMinIntervalMinutes ?: 0L) * 60_000L
        val interval = maxOf(schedule.intervalMs, tierMs)

        if (schedule != Schedule.TIER && Instant.ofEpochMilli(now).atZone(zone).hour < hour) {
            return false
        }

        if (lastUploadAt == null) {
            return true
        }

        val slack = if (schedule == Schedule.TIER) 0L else minOf(SLACK_MS, interval / 4)

        return now - lastUploadAt >= maxOf(interval - slack, tierMs)
    }

    companion object {
        const val MAX_MC_SERVERS = 100
        private const val SLACK_MS = 60L * 60 * 1000

        fun fromJson(json: JsonObject?): RemoteBackupSettings? {
            json ?: return null

            val schedule = Schedule.values().firstOrNull { it.name == json.getValue("schedule") } ?: return null
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

/** Everything persisted about Pano Backup / transfer on this Pano. */
data class RemoteBackupState(
    val links: Map<LinkPurpose, HostLinkState> = emptyMap(),
    val settings: RemoteBackupSettings = RemoteBackupSettings(),
    val lastUploadAt: Long? = null
) {
    fun toJson(): JsonObject = JsonObject()
        .put("links", JsonObject().also { json -> links.forEach { (purpose, link) -> json.put(purpose.name, link.toJson()) } })
        .put("settings", settings.toJson())
        .put("lastUploadAt", lastUploadAt)

    companion object {
        const val OPTION = "pano_backup_remote"

        fun parse(value: String?): RemoteBackupState = try {
            val json = value?.let { JsonObject(it) } ?: JsonObject()
            val links = json.getJsonObject("links") ?: JsonObject()

            RemoteBackupState(
                links = LinkPurpose.values().mapNotNull { purpose -> HostLinkState.fromJson(links.getJsonObject(purpose.name))?.let { purpose to it } }.toMap(),
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
