package com.panomc.platform.backup.remote

import com.panomc.platform.backup.PanoBackupInfo
import com.panomc.platform.backup.PanoBackupStore
import com.panomc.platform.backup.PanoBackupTag
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class BackupTargetType { LOCAL, PANO_BACKUP }

/** What an archive being stored is: `kind` per archive-format.md, [subject] (e.g. the MC server uuid). */
data class StoredArchive(
    val kind: String,
    val subject: String? = null,
    val encrypted: Boolean,
    /** Small, non-secret summary the target may keep next to the archive. */
    val summary: JsonObject? = null
)

/**
 * Where finished `.panoarc` files go and come back from. [put] takes ownership of [file] (moves or
 * uploads it; the caller deletes it afterwards either way) and returns the archive's id there.
 */
interface BackupTarget {
    val type: BackupTargetType

    /** [onStarted] gets the id as soon as the target knows it (before the bytes are sent). */
    suspend fun put(file: File, archive: StoredArchive, onStarted: (String) -> Unit = {}, onProgress: (Long) -> Unit = {}): String

    suspend fun fetch(id: String, target: File, onProgress: (Long) -> Unit = {})

    suspend fun delete(id: String)
}

/** This server's `pano-backups/` folder (pano-instance archives only). */
class LocalBackupTarget(private val store: PanoBackupStore, private val panoVersion: String) : BackupTarget {
    override val type = BackupTargetType.LOCAL

    override suspend fun put(file: File, archive: StoredArchive, onStarted: (String) -> Unit, onProgress: (Long) -> Unit): String = withContext(Dispatchers.IO) {
        require(archive.kind == KIND_PANO_INSTANCE) { "The local folder only keeps Pano backups." }

        val id = store.newId()
        val part = store.partFile(id)

        onStarted(id)

        Files.move(file.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING)
        store.commit(
            PanoBackupInfo(
                id = id,
                createdAt = System.currentTimeMillis(),
                sizeBytes = part.length(),
                encrypted = archive.encrypted,
                tag = PanoBackupTag.MANUAL,
                panoVersion = archive.summary?.getString("panoVersion") ?: panoVersion,
                fileCount = archive.summary?.getInteger("fileCount") ?: 0,
                dbSizeBytes = archive.summary?.getLong("dbSizeBytes") ?: 0L
            )
        )
        onProgress(store.archiveFile(id).length())

        id
    }

    override suspend fun fetch(id: String, target: File, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        store.get(id) ?: throw PanoHostException(NOT_FOUND)

        store.archiveFile(id).copyTo(target, overwrite = true)
        onProgress(target.length())
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { store.delete(id) }
    }

    companion object {
        const val KIND_PANO_INSTANCE = "pano-instance"
        const val NOT_FOUND = "BACKUP_NOT_FOUND"
    }
}

/**
 * The connected account's Pano Backup area: upload session (account quota enforced by Pano Host) →
 * presigned part PUTs with retry → complete with the sha256; downloads via a presigned GET, checked
 * against size + sha256. Between parts the backup's status is polled: stopped on the website
 * (`CANCELED`) ends the upload with [PanoHostException.STOPPED_REMOTELY]. A failed upload deletes its
 * session.
 */
class PanoBackupTarget(private val client: PanoHostClient) : BackupTarget {
    override val type = BackupTargetType.PANO_BACKUP

    override suspend fun put(file: File, archive: StoredArchive, onStarted: (String) -> Unit, onProgress: (Long) -> Unit): String {
        val size = file.length()
        val sha256 = withContext(Dispatchers.IO) { PanoHostClient.sha256(file) }
        val session = client.startBackup(size, archive.kind, archive.subject)

        onStarted(session.id)

        try {
            client.uploadParts(file, session, beforePart = { part -> if (part > 1) checkNotStopped(session.id) }, onProgress = onProgress)
            client.completeBackup(session.id, sha256, archive.summary)
        } catch (e: Throwable) {
            val stopped = e is PanoHostException && (e.code == PanoHostException.STOPPED_REMOTELY || isStopped(e) || runCatching { stopped(session.id) }.getOrDefault(false))

            if (stopped) {
                throw PanoHostException(PanoHostException.STOPPED_REMOTELY, extras = JsonObject().put("backupId", session.id), cause = e)
            }

            runCatching { client.deleteBackup(session.id) }

            throw e
        }

        return session.id
    }

    private suspend fun stopped(id: String) = client.getBackup(id).getString("status") == CANCELED

    private suspend fun checkNotStopped(id: String) {
        if (stopped(id)) throw PanoHostException(PanoHostException.STOPPED_REMOTELY)
    }

    /** `complete` of a backup stopped meanwhile: `BACKUP_NOT_FOUND {reason CANCELED}`. */
    private fun isStopped(e: PanoHostException) = e.code == LocalBackupTarget.NOT_FOUND && e.extras.getString("reason") == CANCELED

    override suspend fun fetch(id: String, target: File, onProgress: (Long) -> Unit) {
        val download = client.downloadBackup(id)

        client.download(
            download.getString("url") ?: throw PanoHostException(PanoHostException.DOWNLOAD_FAILED),
            target,
            download.getLong("sizeBytes"),
            download.getString("sha256"),
            onProgress
        )
    }

    override suspend fun delete(id: String) = client.deleteBackup(id)

    companion object {
        const val CANCELED = "CANCELED"
    }
}
