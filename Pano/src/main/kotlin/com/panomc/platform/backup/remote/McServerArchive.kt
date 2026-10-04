package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveLimits
import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArchive
import io.vertx.core.json.JsonObject
import java.io.File

/**
 * A managed MC server backup ready to leave the node: [fetch] writes pano-node's FULL backup zip
 * to a file; [meta] is its `BackupMeta` (name, sizes, sha256, scope, …); [subject] identifies the
 * server at Pano Backup (its uuid) so the tier frequency applies per server.
 */
class McServerBackupSource(
    val serverId: Long,
    val subject: String,
    val backupId: String,
    val meta: JsonObject,
    val fetch: suspend (File) -> Unit
)

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
}
