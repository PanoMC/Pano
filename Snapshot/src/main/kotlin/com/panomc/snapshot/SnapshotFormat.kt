package com.panomc.snapshot

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Names, layout and the JSON / gzip encoding of a snapshot repository, shared by every Pano implementation. */
object SnapshotFormat {
    /** Local repository (pano-node): chunk files under `data/`. */
    const val FORMAT_VERSION = 1

    /** Remote repository: chunks grouped in pack files, `config.json` carries `pack`. */
    const val PACK_FORMAT_VERSION = 2

    const val CHUNKER_NAME = "fastcdc"
    const val SCOPE_PANO_INSTANCE = "PANO_INSTANCE"

    const val CONFIG_FILE = "config.json"
    const val DATA_DIRECTORY = "data"
    const val SNAPSHOTS_DIRECTORY = "snapshots"
    const val MANIFEST_SUFFIX = ".json.gz"

    /** The most JSON a manifest may inflate to when read from untrusted storage. */
    const val DEFAULT_MAX_MANIFEST_JSON_BYTES = 256L * 1024 * 1024

    private const val IO_BUFFER = 64 * 1024

    private val CHUNK_ID = Regex("^[0-9a-f]{64}$")
    private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun isChunkId(id: String) = CHUNK_ID.matches(id)

    fun isSafeId(id: String) = SAFE_ID.matches(id) && ".." !in id

    /** The repo path of chunk [id] (`data/<hh>/<sha256>`), which a sealed chunk is bound to whichever pack it sits in. */
    fun chunkPath(id: String): String {
        require(isChunkId(id)) { "Not a chunk id." }

        return "$DATA_DIRECTORY/${id.substring(0, 2)}/$id"
    }

    /** The repo path of the manifest of [snapshotId] (`snapshots/<id>.json.gz`). */
    fun manifestPath(snapshotId: String): String {
        require(isSafeId(snapshotId)) { "Not a usable snapshot id." }

        return "$SNAPSHOTS_DIRECTORY/$snapshotId$MANIFEST_SUFFIX"
    }

    /** Gson exactly as pano-node writes: compact, no HTML escaping, nulls left out. */
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun configJson(config: SnapshotRepoConfig): ByteArray = gson.toJson(config).toByteArray(Charsets.UTF_8)

    /** The config in [bytes]; call [SnapshotRepoConfig.validate] to see whether it is one this code can use. */
    fun parseConfig(bytes: ByteArray): SnapshotRepoConfig =
        gson.fromJson(String(bytes, Charsets.UTF_8), SnapshotRepoConfig::class.java)
            ?: throw IllegalArgumentException("Empty snapshot repository config.")

    fun manifestJson(manifest: SnapshotManifest): ByteArray = gson.toJson(manifest).toByteArray(Charsets.UTF_8)

    /** Writes the manifest as gzip-compressed JSON (Java `GZIPOutputStream`, like Node) to [output], which stays open. */
    fun writeManifest(manifest: SnapshotManifest, output: OutputStream) {
        val gzip = GZIPOutputStream(output, IO_BUFFER)
        val writer = gzip.bufferedWriter()

        gson.toJson(manifest, writer)
        writer.flush()
        gzip.finish()
    }

    /** The manifest object's plaintext: gzip of [manifestJson]. */
    fun encodeManifest(manifest: SnapshotManifest): ByteArray {
        val out = ByteArrayOutputStream()

        writeManifest(manifest, out)

        return out.toByteArray()
    }

    /** Reads a gzip manifest from [input] (closed afterwards); [maxJsonBytes] caps the inflated JSON. */
    fun readManifest(input: InputStream, maxJsonBytes: Long = DEFAULT_MAX_MANIFEST_JSON_BYTES): SnapshotManifest =
        CappedInputStream(GZIPInputStream(input, IO_BUFFER), maxJsonBytes).bufferedReader(Charsets.UTF_8).use { reader ->
            gson.fromJson(reader, SnapshotManifest::class.java)
                ?: throw IllegalStateException("The snapshot manifest is empty.")
        }

    /** Parses a gzip manifest; [maxJsonBytes] caps the inflated JSON. */
    fun decodeManifest(gz: ByteArray, maxJsonBytes: Long = DEFAULT_MAX_MANIFEST_JSON_BYTES): SnapshotManifest =
        readManifest(ByteArrayInputStream(gz), maxJsonBytes)

    private class CappedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
        private var total = 0L

        override fun read(): Int {
            val value = inner.read()

            if (value >= 0) {
                count(1)
            }

            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = inner.read(buffer, offset, length)

            if (read > 0) {
                count(read)
            }

            return read
        }

        private fun count(bytes: Int) {
            total += bytes

            if (total > limit) {
                throw IOException("The snapshot manifest is too large.")
            }
        }

        override fun close() = inner.close()
    }
}
