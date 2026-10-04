package com.panomc.node.backup.snapshot

/**
 * `repo/config.json`: which chunker a repository was written with.
 *
 * Read back on every operation rather than assumed, so a repository keeps the parameters it was
 * started with even if the defaults ever change — chunks cut with other sizes would never match
 * the ones already stored, and the first snapshot after such a change would quietly be a full one.
 */
data class SnapshotRepoConfig(
    val version: Int = SnapshotRepository.FORMAT_VERSION,
    val chunker: String = SnapshotRepository.CHUNKER_NAME,
    val min: Int = FastCdc.DEFAULT_MIN,
    val avg: Int = FastCdc.DEFAULT_AVG,
    val max: Int = FastCdc.DEFAULT_MAX
)

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
    val version: Int = SnapshotRepository.FORMAT_VERSION,
    val id: String = "",
    val name: String = "",
    val createdAt: Long = 0,
    val scope: String = "ALL",
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val roots: List<String> = emptyList(),
    val entries: List<SnapshotEntry> = emptyList()
) {
    /** Every chunk this snapshot needs, each once. */
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
}

/** What creating one snapshot did, for its meta file and the task's last line. */
data class SnapshotResult(
    val manifest: SnapshotManifest,
    /** Chunks this snapshot wrote that the repository did not already hold. */
    val newChunks: Int,
    /** Distinct chunks the snapshot references, new or not. */
    val totalChunks: Int,
    /** Bytes of chunk files this snapshot added to the disk. */
    val storedBytes: Long
)

/** What a garbage collection removed. [skipped] means a manifest could not be read and nothing was touched. */
data class SnapshotGcResult(
    val deletedChunks: Int,
    val freedBytes: Long,
    val skipped: Boolean = false
)

/** Another create, delete or restore already holds the repository. The message is the wire code. */
class RepoBusyException : RuntimeException(SnapshotRepository.ERROR_REPO_BUSY)

/**
 * A chunk a snapshot needs is missing or does not hash to its name.
 *
 * The message is the wire error — `CORRUPT_CHUNK <sha>` — so it can be reported as it is.
 */
class CorruptChunkException(val chunkId: String) :
    RuntimeException("${SnapshotRepository.ERROR_CORRUPT_CHUNK} $chunkId")
