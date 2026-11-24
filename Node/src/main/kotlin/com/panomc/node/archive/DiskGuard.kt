package com.panomc.node.archive

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Stops a write before it fills the disk.
 *
 * Uploads and extraction have no size ceiling any more: the disk is the only limit. Running a
 * disk to zero takes the server, the node's own logs and config writes down with it, so every
 * byte an upload or extraction writes is counted here and the free space is re-read every
 * [CHECK_EVERY] bytes (a `statvfs` per chunk, not per write). Once less than [reserveBytes] would
 * be left, [NotEnoughSpaceException] is thrown and the caller deletes what it wrote.
 *
 * Not thread-safe; one guard belongs to one extraction.
 */
class DiskGuard(
    private val directory: File,
    private val reserveBytes: Long = DEFAULT_RESERVE,
    private val usableSpace: (File) -> Long = { it.usableSpace }
) {
    /** Starts full, so the very first write checks the disk at once. */
    private var sinceCheck = CHECK_EVERY

    /** Free space right now, read on the nearest directory that exists (0 is reported for a missing one). */
    fun free(): Long {
        var candidate: File? = directory.absoluteFile

        while (candidate != null && !candidate.exists()) {
            candidate = candidate.parentFile
        }

        return usableSpace(candidate ?: directory)
    }

    /** Refuses up front when [bytes] more would leave less than the reserve. */
    fun require(bytes: Long) {
        val free = free()

        if (free < bytes + reserveBytes) {
            throw NotEnoughSpaceException(bytes + reserveBytes, free)
        }
    }

    /** Throws when the disk is already below the reserve. */
    fun check() {
        val free = free()

        if (free < reserveBytes) {
            throw NotEnoughSpaceException(null, free)
        }
    }

    /** Whether the disk is below the reserve now; used to tell "disk full" from other write errors. */
    fun isLow(): Boolean = free() < reserveBytes

    /** Counts [bytes] about to be written and re-checks the disk every [CHECK_EVERY]. */
    fun written(bytes: Long) {
        sinceCheck += bytes

        if (sinceCheck >= CHECK_EVERY) {
            sinceCheck = 0

            check()
        }
    }

    /** [out], with every write counted before it happens. */
    fun wrap(out: OutputStream): OutputStream = object : FilterOutputStream(out) {
        override fun write(b: Int) {
            written(1)
            out.write(b)
        }

        // FilterOutputStream's own version writes byte by byte.
        override fun write(b: ByteArray, off: Int, len: Int) {
            written(len.toLong())
            out.write(b, off, len)
        }
    }

    companion object {
        /** Kept free at all times: enough for logs, config writes and a server's own saves. */
        const val DEFAULT_RESERVE = 256L * 1024 * 1024

        /** How many bytes may be written between two free-space reads. */
        const val CHECK_EVERY = 8L * 1024 * 1024
    }
}
