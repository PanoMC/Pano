package com.panomc.platform.backup.remote

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
