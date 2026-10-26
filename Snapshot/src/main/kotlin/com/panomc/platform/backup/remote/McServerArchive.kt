package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveLimits
import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcKeys
import com.panomc.platform.archive.PanoArchive
import io.vertx.core.json.JsonObject
import java.io.File

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
