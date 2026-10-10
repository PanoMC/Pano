package com.panomc.node.backup.snapshot

import com.panomc.snapshot.SnapshotManifest

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
