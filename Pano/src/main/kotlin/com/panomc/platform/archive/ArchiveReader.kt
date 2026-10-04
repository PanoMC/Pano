package com.panomc.platform.archive

import com.panomc.platform.archive.PanoArcException.Code
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Reads an archive zip as a stream (it usually comes out of a [PanoArcEnvelope] decrypting
 * stream, so there is no random access) and trusts nothing in it:
 *
 * - **zip slip**: every entry name must pass [ArchivePaths.validate] and resolve inside the target;
 *   an archive with such a name is refused outright, not partially extracted around it,
 * - **zip bombs**: entry count, per-entry and total decompressed bytes are capped by [limits]
 *   while decompressing, whatever the entry headers declare,
 * - **integrity**: `manifest.json` must be the last entry, every other entry must be listed in it
 *   exactly once with a matching size and sha256, nothing listed may be missing, and the manifest's
 *   own `files`/`db` summaries must match. The underlying stream is then read to its end, so an
 *   envelope's final-chunk check always runs.
 *
 * Extraction goes into a fresh staging directory the caller owns and deletes on failure; nothing is
 * ever written over existing files.
 */
class ArchiveReader(private val limits: ArchiveLimits = ArchiveLimits()) {
    /** Extracts into [target] (created if missing, should be empty) and returns the verified manifest. */
    fun extract(input: InputStream, target: File): ArchiveManifest {
        Files.createDirectories(target.toPath())

        return read(input, target.canonicalFile)
    }

    /** Verifies everything without writing anything. */
    fun verify(input: InputStream): ArchiveManifest = read(input, null)

    private fun read(input: InputStream, target: File?): ArchiveManifest {
        val actual = LinkedHashMap<String, ArchiveEntry>()
        val buffer = ByteArray(BUFFER_SIZE)
        var manifestBytes: ByteArray? = null
        var totalBytes = 0L
        var count = 0

        try {
            val zip = ZipInputStream(input)

            while (true) {
                val entry = zip.nextEntry ?: break

                if (++count > limits.maxEntries + 1) {
                    throw PanoArcException(Code.TOO_MANY_ENTRIES)
                }

                if (manifestBytes != null) {
                    throw PanoArcException(Code.INVALID_ARCHIVE, "manifest.json is not the last entry.")
                }

                if (entry.isDirectory) {
                    throw PanoArcException(Code.INVALID_ARCHIVE, "Unexpected directory entry ${entry.name}.")
                }

                val name = ArchivePaths.validate(entry.name)

                if (name == ArchiveManifest.MANIFEST_ENTRY) {
                    manifestBytes = readLimited(zip, limits.maxManifestBytes)

                    continue
                }

                if (actual.containsKey(name)) {
                    throw PanoArcException(Code.INVALID_ARCHIVE, "Duplicate entry $name.")
                }

                val sink = target?.let { open(it, name) }
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L

                sink.use { out ->
                    while (true) {
                        val read = zip.read(buffer)

                        if (read < 0) {
                            break
                        }

                        size += read
                        totalBytes += read

                        if (size > limits.maxEntryBytes || totalBytes > limits.maxTotalBytes) {
                            throw PanoArcException(Code.TOO_LARGE, "The archive unpacks to more than the allowed size.")
                        }

                        digest.update(buffer, 0, read)
                        out?.write(buffer, 0, read)
                    }
                }

                actual[name] = ArchiveEntry(name, size, ArchiveManifest.hex(digest.digest()))
            }

            // The central directory and, for an envelope, the final chunk come after the last entry.
            drain(input)
        } catch (e: ZipException) {
            throw PanoArcException(Code.INVALID_ARCHIVE, e.message, e)
        }

        val manifest = ArchiveManifest.parse(
            manifestBytes ?: throw PanoArcException(Code.INVALID_ARCHIVE, "The archive has no manifest.json.")
        )

        verifyManifest(manifest, actual)

        return manifest
    }

    private fun verifyManifest(manifest: ArchiveManifest, actual: Map<String, ArchiveEntry>) {
        val listed = HashMap<String, ArchiveEntry>()

        manifest.entries.forEach {
            if (listed.put(it.path, it) != null) {
                throw PanoArcException(Code.INVALID_ARCHIVE, "The manifest lists ${it.path} twice.")
            }
        }

        actual.values.forEach { entry ->
            val expected = listed[entry.path]
                ?: throw PanoArcException(Code.HASH_MISMATCH, "${entry.path} is not in the manifest.")

            if (expected.size != entry.size || !expected.sha256.equals(entry.sha256, ignoreCase = true)) {
                throw PanoArcException(Code.HASH_MISMATCH, "${entry.path} does not match its manifest hash.")
            }
        }

        listed.keys.firstOrNull { !actual.containsKey(it) }?.let {
            throw PanoArcException(Code.HASH_MISMATCH, "$it is missing from the archive.")
        }

        if (ArchiveManifest.filesInfo(manifest.entries) != manifest.files) {
            throw PanoArcException(Code.HASH_MISMATCH, "The manifest's file summary does not match its entries.")
        }

        val dump = listed[ArchiveManifest.DB_DUMP_ENTRY]
        val db = manifest.db

        if ((dump == null) != (db == null) ||
            (dump != null && db != null && (db.sizeBytes != dump.size || !db.sha256.equals(dump.sha256, ignoreCase = true)))
        ) {
            throw PanoArcException(Code.HASH_MISMATCH, "The manifest's database summary does not match the dump.")
        }
    }

    private fun open(target: File, name: String): OutputStream {
        val destination = File(target, name).canonicalFile

        if (!destination.toPath().startsWith(target.toPath()) || destination == target) {
            throw PanoArcException(Code.UNSAFE_ENTRY, "Entry $name escapes the extraction directory.")
        }

        Files.createDirectories(destination.parentFile.toPath())

        // CREATE_NEW: never write through a pre-existing file or link.
        return Files.newOutputStream(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)

        while (true) {
            val read = input.read(buffer)

            if (read < 0) {
                break
            }

            out.write(buffer, 0, read)

            if (out.size() > max) {
                throw PanoArcException(Code.TOO_LARGE, "manifest.json is too large.")
            }
        }

        return out.toByteArray()
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        var drained = 0L

        while (true) {
            val read = input.read(buffer)

            if (read < 0) {
                return
            }

            drained += read

            if (drained > MAX_TRAILER_BYTES) {
                throw PanoArcException(Code.TOO_LARGE, "Too much data after the last entry.")
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /** The central directory; ~100 bytes per entry, so this covers the entry limit with room. */
        private const val MAX_TRAILER_BYTES = 256L * 1024 * 1024
    }
}
