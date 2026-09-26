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
 * The account's Pano Backup area through a `BACKUP` link: upload session (tier quota + frequency
 * enforced by Pano Host) → presigned part PUTs with retry → complete with the sha256; downloads via
 * a presigned GET, checked against size + sha256. A failed upload deletes its session.
 */
class PanoBackupTarget(private val client: PanoHostClient, private val token: suspend () -> String) : BackupTarget {
    override val type = BackupTargetType.PANO_BACKUP

    override suspend fun put(file: File, archive: StoredArchive, onStarted: (String) -> Unit, onProgress: (Long) -> Unit): String {
        val size = file.length()
        val sha256 = withContext(Dispatchers.IO) { PanoHostClient.sha256(file) }
        val token = token()
        val session = client.startBackup(token, size, archive.kind, archive.subject)

        onStarted(session.id)

        try {
            client.uploadParts(file, session, onProgress)
            client.completeBackup(token, session.id, sha256, archive.summary)
        } catch (e: Throwable) {
            runCatching { client.deleteBackup(token, session.id) }

            throw e
        }

        return session.id
    }

    override suspend fun fetch(id: String, target: File, onProgress: (Long) -> Unit) {
        val download = client.downloadBackup(token(), id)

        client.download(
            download.getString("url") ?: throw PanoHostException(PanoHostException.DOWNLOAD_FAILED),
            target,
            download.getLong("sizeBytes"),
            download.getString("sha256"),
            onProgress
        )
    }

    override suspend fun delete(id: String) = client.deleteBackup(token(), id)
}
