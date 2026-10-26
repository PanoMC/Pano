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
    /** The node sends the backup itself (protocol 8); null = [fetch] it to Pano and upload from here. */
    val direct: McServerDirectUpload? = null,
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
 * A backup the node sends to Pano Backup itself. [upload] hands [request] to the node with a ticket answered by [handler]
 * and returns the node's answer: `{size, sha256}` for an archive, `{stats}` for a snapshot.
 */
fun interface McServerDirectUpload {
    suspend fun upload(request: McServerDirectRequest, handler: com.panomc.platform.backup.DirectUploadHandler): JsonObject
}

/**
 * What the node needs for a direct upload: [archive] mode with the meta and, for an encrypted archive, the key Pano
 * derived for it ([encryption]); or [snapshot] mode with the open snapshot session.
 */
class McServerDirectRequest(
    val snapshot: SnapshotSession?,
    val meta: JsonObject,
    val producer: String,
    val instanceName: String?,
    val encryption: com.panomc.platform.archive.PanoArcEncryption.DerivedKey? = null,
    val snapshotName: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val archive: Boolean get() = snapshot == null
}

/**
 * Puts a managed server backup taken out of Pano Backup into a managed server's own backups: [import] hands the
 * node's zip + its meta to the server [serverId] and returns the id of the backup it became there.
 */
fun interface McServerBackupImporter {
    suspend fun import(serverId: Long, zip: File, meta: JsonObject, onProgress: (Long) -> Unit): String
}
