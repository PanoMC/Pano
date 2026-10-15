package com.panomc.snapshot

/**
 * `repo/config.json`: which chunker a repository was written with.
 *
 * Read back on every operation rather than assumed, so a repository keeps the parameters it was
 * started with even if the defaults ever change — chunks cut with other sizes would never match
 * the ones already stored, and the first snapshot after such a change would quietly be a full one.
 *
 * A version 1 repository (pano-node's, local files) has exactly the first five fields. A version 2
 * repository (remote storage, chunks grouped in pack files) adds [pack], the chunk bytes after which
 * a pack is closed; Gson leaves a null out, so a version 1 config is written byte for byte as before.
 */
data class SnapshotRepoConfig(
    val version: Int = SnapshotFormat.FORMAT_VERSION,
    val chunker: String = SnapshotFormat.CHUNKER_NAME,
    val min: Int = FastCdc.DEFAULT_MIN,
    val avg: Int = FastCdc.DEFAULT_AVG,
    val max: Int = FastCdc.DEFAULT_MAX,
    val pack: Long? = null
) {
    /** Whether this is a repository whose chunks live in pack files. */
    val isPackRepository: Boolean
        get() = version == SnapshotFormat.PACK_FORMAT_VERSION

    /**
     * Throws unless this is a repository this code can read and write: version 1 without [pack] or
     * version 2 with [pack] in [SnapshotPack.MIN_TARGET_BYTES]..[SnapshotPack.MAX_TARGET_BYTES], the
     * `fastcdc` chunker and chunk sizes [FastCdc] accepts.
     */
    fun validate(): SnapshotRepoConfig {
        require(chunker == SnapshotFormat.CHUNKER_NAME) { "Unsupported snapshot repository ($chunker v$version)." }

        when (version) {
            SnapshotFormat.FORMAT_VERSION -> require(pack == null) { "A version 1 snapshot repository has no pack size." }
            SnapshotFormat.PACK_FORMAT_VERSION -> require(pack != null && pack in SnapshotPack.MIN_TARGET_BYTES..SnapshotPack.MAX_TARGET_BYTES) {
                "Unusable pack size."
            }

            else -> throw IllegalArgumentException("Unsupported snapshot repository ($chunker v$version).")
        }

        FastCdc(min, avg, max)

        return this
    }
}

/**
 * One path in a snapshot, with the short field names the wire format uses.
 *
 * [p] is the server-relative path with `/`, [t] is `f` for a file or `d` for a directory, [s] the
 * file's size, [m] its modification time in epoch milliseconds, and [c] the SHA-256 ids of the
 * chunks that rebuild it, in order. Directories carry neither [s] nor [c]; Gson leaves nulls out,
 * which is what keeps them out of the JSON.
 */
data class SnapshotEntry(
    val p: String = "",
    val t: String = TYPE_FILE,
    val s: Long? = null,
    val m: Long? = null,
    val c: List<String>? = null
) {
    val isFile: Boolean
        get() = t == TYPE_FILE

    val isDirectory: Boolean
        get() = t == TYPE_DIRECTORY

    companion object {
        const val TYPE_FILE = "f"
        const val TYPE_DIRECTORY = "d"
    }
}

/**
 * `repo/snapshots/<backupId>.json.gz`: everything a snapshot is, apart from the bytes themselves.
 *
 * A complete view of its scope rather than a diff against a parent, which is the property that
 * lets any snapshot be deleted without touching the others: the parent is only ever consulted
 * while *creating* a snapshot, to skip reading files that have not changed, and never while
 * restoring one.
 */
data class SnapshotManifest(
    val version: Int = SnapshotFormat.FORMAT_VERSION,
    val id: String = "",
    val name: String = "",
    val createdAt: Long = 0,
    val scope: String = "ALL",
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val roots: List<String> = emptyList(),
    val entries: List<SnapshotEntry> = emptyList()
) {
    /** Every chunk this snapshot needs, each once, in first-use order. */
    fun chunkIds(): Set<String> {
        val result = LinkedHashSet<String>()

        entries.forEach { entry -> entry.c?.let { result.addAll(it) } }

        return result
    }

    val fileCount: Long
        get() = entries.count { it.isFile }.toLong()

    /** The total size of the files in it, uncompressed and before any deduplication. */
    val logicalBytes: Long
        get() = entries.sumOf { if (it.isFile) it.s ?: 0L else 0L }

    /** The same manifest with its entries in the canonical order (Java `String` order of `p`). */
    fun sorted(): SnapshotManifest = copy(entries = entries.sortedBy { it.p })
}
