package com.panomc.platform.backup

import io.vertx.core.json.JsonObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Why a local backup exists; drives retention. */
enum class PanoBackupTag {
    /** Taken by an admin; kept until deleted. */
    MANUAL,

    /** Taken by the schedule; the newest `keep` are kept. */
    SCHEDULED,

    /** Safety archive taken right before a restore; pruned after [PanoBackupStore.PRE_RESTORE_TTL_MS]. */
    PRE_RESTORE
}

/** One local backup: `<id>.panoarc` plus its `<id>.json` sidecar. */
data class PanoBackupInfo(
    val id: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val encrypted: Boolean,
    val tag: PanoBackupTag,
    val panoVersion: String,
    val fileCount: Int,
    val dbSizeBytes: Long,
    val createdBy: String? = null
) {
    fun toJson(): JsonObject = JsonObject()
        .put("id", id)
        .put("createdAt", createdAt)
        .put("sizeBytes", sizeBytes)
        .put("encrypted", encrypted)
        .put("tag", tag.name)
        .put("panoVersion", panoVersion)
        .put("fileCount", fileCount)
        .put("dbSizeBytes", dbSizeBytes)
        .put("createdBy", createdBy)

    val fileName: String get() = "pano-backup-$id${PanoBackupStore.EXTENSION}"

    companion object {
        fun fromJson(json: JsonObject) = PanoBackupInfo(
            id = json.getString("id"),
            createdAt = json.getLong("createdAt"),
            sizeBytes = json.getLong("sizeBytes"),
            encrypted = json.getBoolean("encrypted", false),
            tag = PanoBackupTag.values().firstOrNull { it.name == json.getString("tag") } ?: PanoBackupTag.MANUAL,
            panoVersion = json.getString("panoVersion", ""),
            fileCount = json.getInteger("fileCount", 0),
            dbSizeBytes = json.getLong("dbSizeBytes", 0L),
            createdBy = json.getString("createdBy")
        )
    }
}

/**
 * Local backups on disk, outside every archived directory (so a backup never contains the previous
 * backups and a restore never replaces them). Metadata lives in a sidecar next to each archive, not
 * in the database, because a restore replaces the database.
 */
class PanoBackupStore(val directory: File) {
    private val random = SecureRandom()

    fun newId(now: Long = System.currentTimeMillis()): String {
        val stamp = ID_TIME.format(Instant.ofEpochMilli(now))
        val suffix = (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

        return "$stamp-$suffix"
    }

    /** Where a new backup is written before [commit] makes it visible. */
    fun partFile(id: String): File {
        requireValid(id)
        directory.mkdirs()

        return File(directory, "$id$EXTENSION.part")
    }

    fun commit(info: PanoBackupInfo) {
        val part = partFile(info.id)
        val target = archiveFile(info.id)

        File(directory, "${info.id}$META_EXTENSION").writeText(info.toJson().encodePrettily())

        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun list(): List<PanoBackupInfo> {
        val files = directory.listFiles { file -> file.name.endsWith(META_EXTENSION) } ?: return emptyList()

        return files.mapNotNull { meta ->
            val id = meta.name.removeSuffix(META_EXTENSION)

            if (!isValidId(id) || !archiveFile(id).isFile) {
                return@mapNotNull null
            }

            try {
                PanoBackupInfo.fromJson(JsonObject(meta.readText())).takeIf { it.id == id }
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.createdAt }
    }

    fun get(id: String): PanoBackupInfo? = if (isValidId(id)) list().firstOrNull { it.id == id } else null

    fun archiveFile(id: String): File {
        requireValid(id)

        return File(directory, "$id$EXTENSION")
    }

    fun delete(id: String): Boolean {
        if (!isValidId(id)) {
            return false
        }

        val existed = archiveFile(id).exists()

        archiveFile(id).delete()
        File(directory, "$id$META_EXTENSION").delete()
        File(directory, "$id$EXTENSION.part").delete()

        return existed
    }

    /** Removes half-written archives a crash left behind. */
    fun cleanParts() {
        directory.listFiles { file -> file.name.endsWith("$EXTENSION.part") }?.forEach { it.delete() }
    }

    /**
     * Retention: scheduled backups beyond the newest [keepScheduled] and pre-restore safety archives
     * older than [PRE_RESTORE_TTL_MS] are deleted. Manual backups are never touched.
     */
    fun prune(keepScheduled: Int, now: Long = System.currentTimeMillis()): List<String> {
        val all = list()
        val scheduled = all.filter { it.tag == PanoBackupTag.SCHEDULED }.drop(keepScheduled.coerceAtLeast(0))
        val expired = all.filter { it.tag == PanoBackupTag.PRE_RESTORE && now - it.createdAt > PRE_RESTORE_TTL_MS }

        return (scheduled + expired).map { it.id }.onEach { delete(it) }
    }

    companion object {
        const val EXTENSION = ".panoarc"
        const val META_EXTENSION = ".json"
        const val PRE_RESTORE_TTL_MS = 7L * 24 * 60 * 60 * 1000

        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val ID_REGEX = Regex("^[0-9]{8}-[0-9]{6}-[a-z0-9]{6}$")
        private val ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

        fun isValidId(id: String?) = id != null && ID_REGEX.matches(id)

        private fun requireValid(id: String) = require(isValidId(id)) { "Invalid backup id." }
    }
}
