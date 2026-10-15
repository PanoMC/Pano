package com.panomc.snapshot

/**
 * A chunk a snapshot needs is missing or does not hash to its name.
 *
 * The message is the wire error — `CORRUPT_CHUNK <sha>` — so it can be reported as it is.
 */
class CorruptChunkException(val chunkId: String) : RuntimeException("$ERROR_CORRUPT_CHUNK ${chunkId.take(64)}") {
    companion object {
        const val ERROR_CORRUPT_CHUNK = "CORRUPT_CHUNK"
    }
}

/**
 * A sealed object, pack file or pack index failed its checks: foreign header, unknown key, wrong
 * repository path, tampering or a structure that does not add up. The message starts with the wire
 * code (`SNAPSHOT_OBJECT_INVALID`, `SNAPSHOT_KEY_UNKNOWN`, `SNAPSHOT_OBJECT_TAMPERED`,
 * `SNAPSHOT_PACK_INVALID`).
 */
class SnapshotIntegrityException(message: String) : RuntimeException(message)
