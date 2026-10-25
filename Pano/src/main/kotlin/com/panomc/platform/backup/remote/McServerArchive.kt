package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveLimits
import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcKeys
import com.panomc.platform.archive.PanoArchive
import com.panomc.platform.server.backup.ServerPanoBackupSettings
import io.vertx.core.json.JsonObject
import java.io.File

/**
 * A managed MC server backup ready to leave the node: [fetch] writes pano-node's FULL backup zip
 * to a file; [meta] is its `BackupMeta` (name, sizes, sha256, scope, …); [subject] identifies the
 * server at Pano Backup (its uuid), which is what the per-server [settings]' retention counts by.
 */
class McServerBackupSource(
    val serverId: Long,
    val subject: String,
    val backupId: String,
    val meta: JsonObject,
    val settings: ServerPanoBackupSettings = ServerPanoBackupSettings(),
    /** Sent on its own after the server's backup (the server's Pano Backup setting), not by hand: the copy's `trigger`. */
    val automatic: Boolean = false,
    /** The copy is at Pano Backup now, under this id (the backup's row remembers it). */
    val onUploaded: suspend (remoteId: String) -> Unit = {},
    val fetch: suspend (File) -> Unit
) {
    /** A snapshot of the node (`meta.mode`), uploaded into the snapshot repository rather than as an archive. */
    val isSnapshot: Boolean get() = meta.getString("mode") == "SNAPSHOT"

    /** The backup's size as the server reported it (the zip's for a full backup, the files' for a snapshot). */
    val sizeBytes: Long get() = (meta.getValue("sizeBytes") as? Number)?.toLong() ?: 0L

    /** How the copy was started, as Pano Backup lists it: `AUTOMATIC` | `MANUAL`. */
    val trigger: String get() = if (automatic) PanoRemoteBackupService.TRIGGER_AUTOMATIC else PanoRemoteBackupService.TRIGGER_MANUAL
}

/**
 * Puts a managed server backup taken out of Pano Backup into a managed server's own backups: [import] hands the
 * node's zip + its meta to the server [serverId] and returns the id of the backup it became there.
 */
fun interface McServerBackupImporter {
    suspend fun import(serverId: Long, zip: File, meta: JsonObject, onProgress: (Long) -> Unit): String
}

/** `kind: mc-server` archives (archive-format.md §1): the node's zip under `server/` with its meta. */
object McServerArchive {
    const val ZIP_ENTRY = "server/backup.zip"
    const val META_ENTRY = "server/backup-meta.json"

    /** Wraps [zip] into [target] (passphrase E2E when given) and returns the manifest. */
    fun wrap(
        zip: File,
        meta: JsonObject,
        target: File,
        passphrase: CharArray?,
        producer: String,
        instanceName: String?,
        limits: ArchiveLimits = ArchiveLimits()
    ): ArchiveManifest {
        val encryption = passphrase?.takeIf { it.isNotEmpty() }?.let { PanoArcEncryption.Passphrase(it.copyOf()) }

        try {
            PanoArchive.writer(target.outputStream().buffered(256 * 1024), encryption, limits).use { writer ->
                zip.inputStream().buffered(256 * 1024).use { writer.addStream(ZIP_ENTRY, it) }
                writer.addBytes(META_ENTRY, meta.encodePrettily().toByteArray(Charsets.UTF_8))

                return writer.finish(ArchiveManifest.KIND_MC_SERVER, producer, ArchiveSource(instanceName = instanceName, hosted = false))
            }
        } finally {
            (encryption as? PanoArcEncryption.Passphrase)?.passphrase?.fill('\u0000')
        }
    }

    /**
     * Opens an `mc-server` archive into [staging] (decrypted with [passphrase] when it is enveloped) and returns the
     * node's zip and its meta. Anything else is refused with `WRONG_KIND`.
     */
    fun open(archive: File, passphrase: CharArray?, staging: File, limits: ArchiveLimits = ArchiveLimits()): Pair<File, JsonObject> {
        val keys = PanoArcKeys(passphrase = passphrase?.takeIf { it.isNotEmpty() })
        val manifest = archive.inputStream().buffered(256 * 1024).use { PanoArchive.extract(it, keys, staging, limits) }

        if (manifest.kind != ArchiveManifest.KIND_MC_SERVER) {
            throw PanoArcException(PanoArcException.Code.WRONG_KIND, "The archive is a ${manifest.kind}, not a server backup.")
        }

        val zip = File(staging, ZIP_ENTRY)
        val meta = File(staging, META_ENTRY)

        if (!zip.isFile || !meta.isFile) {
            throw PanoArcException(PanoArcException.Code.INVALID_ARCHIVE, "The server backup archive has no backup in it.")
        }

        return zip to JsonObject(meta.readText(Charsets.UTF_8))
    }
}
