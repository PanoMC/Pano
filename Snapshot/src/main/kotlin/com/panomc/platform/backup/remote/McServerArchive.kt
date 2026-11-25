package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchiveLimits
import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcKeys
import com.panomc.platform.archive.PanoArcEnvelope
import com.panomc.platform.archive.PanoArchive
import com.panomc.platform.archive.ArchiveReader
import io.vertx.core.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** `kind: mc-server` archives (archive-format.md §1): the node's zip under `server/` with its meta. */
object McServerArchive {
    const val ZIP_ENTRY = "server/backup.zip"
    const val META_ENTRY = "server/backup-meta.json"

    /**
     * A server backup is as big as the server: no ceiling on what it unpacks to (owner decision 2026-11-25, the admin
     * chose to keep it). Only the entry count and the manifest's size stay bounded.
     */
    val UNLIMITED = ArchiveLimits(maxTotalBytes = Long.MAX_VALUE, maxEntryBytes = Long.MAX_VALUE)

    /** The meta is a few hundred bytes; anything far larger is not a server backup's. */
    private const val MAX_META_BYTES = 1024 * 1024

    /** Wraps [zip] into [target] (passphrase E2E when given) and returns the manifest. */
    fun wrap(
        zip: File,
        meta: JsonObject,
        target: File,
        passphrase: CharArray?,
        producer: String,
        instanceName: String?,
        limits: ArchiveLimits = UNLIMITED
    ): ArchiveManifest {
        val encryption = passphrase?.takeIf { it.isNotEmpty() }?.let { PanoArcEncryption.Passphrase(it.copyOf()) }

        try {
            return wrap(zip, meta, target, encryption, producer, instanceName, limits)
        } finally {
            encryption?.passphrase?.fill('\u0000')
        }
    }

    /** Wraps [zip] into [target] sealed with [encryption] (null = plain), e.g. a [PanoArcEncryption.DerivedKey] on pano-node. */
    fun wrap(
        zip: File,
        meta: JsonObject,
        target: File,
        encryption: PanoArcEncryption?,
        producer: String,
        instanceName: String?,
        limits: ArchiveLimits = UNLIMITED
    ): ArchiveManifest =
        PanoArchive.writer(target.outputStream().buffered(256 * 1024), encryption, limits).use { writer ->
            zip.inputStream().buffered(256 * 1024).use { writer.addStream(ZIP_ENTRY, it) }
            writer.addBytes(META_ENTRY, meta.encodePrettily().toByteArray(Charsets.UTF_8))

            writer.finish(ArchiveManifest.KIND_MC_SERVER, producer, ArchiveSource(instanceName = instanceName, hosted = false))
        }

    /**
     * Opens an `mc-server` archive into [staging] (decrypted with [passphrase] when it is enveloped) and returns the
     * node's zip and its meta. Anything else is refused with `WRONG_KIND`.
     */
    fun open(archive: File, passphrase: CharArray?, staging: File, limits: ArchiveLimits = UNLIMITED): Pair<File, JsonObject> {
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

    /**
     * Reads an `mc-server` archive as it streams in (decrypted with [passphrase] when it is enveloped): the node's zip
     * goes into [zip] as it is read, nothing touches a disk. Returns the meta once the whole archive was read and its
     * manifest verified; until then what [zip] received is not to be trusted (the caller fails its side when this
     * throws). [zip] is not closed.
     */
    fun stream(archive: InputStream, passphrase: CharArray?, zip: OutputStream, limits: ArchiveLimits = UNLIMITED): JsonObject {
        val keys = PanoArcKeys(passphrase = passphrase?.takeIf { it.isNotEmpty() })
        val meta = ByteArrayOutputStream()
        var sawZip = false

        val manifest = PanoArcEnvelope.open(archive, keys).use { input ->
            ArchiveReader(limits).stream(input) { name ->
                when (name) {
                    ZIP_ENTRY -> {
                        sawZip = true

                        object : java.io.FilterOutputStream(zip) {
                            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

                            override fun close() = out.flush()
                        }
                    }

                    META_ENTRY -> object : java.io.FilterOutputStream(meta) {
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (meta.size() + len > MAX_META_BYTES) {
                                throw PanoArcException(PanoArcException.Code.TOO_LARGE, "The server backup's meta is too large.")
                            }

                            out.write(b, off, len)
                        }
                    }

                    else -> null
                }
            }
        }

        if (manifest.kind != ArchiveManifest.KIND_MC_SERVER) {
            throw PanoArcException(PanoArcException.Code.WRONG_KIND, "The archive is a ${manifest.kind}, not a server backup.")
        }

        if (!sawZip || meta.size() == 0) {
            throw PanoArcException(PanoArcException.Code.INVALID_ARCHIVE, "The server backup archive has no backup in it.")
        }

        return JsonObject(meta.toString(Charsets.UTF_8))
    }

    /**
     * Checks [passphrase] against an archive's first bytes ([prefix]: the magic and header are enough) before anything
     * is downloaded: `PASSPHRASE_REQUIRED` / `WRONG_PASSPHRASE` as the real read would say. A plain archive passes.
     */
    fun checkKey(prefix: InputStream, passphrase: CharArray?) {
        val header = PanoArcEnvelope.readHeader(prefix) ?: return

        PanoArcEnvelope.decrypt(InputStream.nullInputStream(), header, PanoArcKeys(passphrase = passphrase?.takeIf { it.isNotEmpty() }))
    }
}
