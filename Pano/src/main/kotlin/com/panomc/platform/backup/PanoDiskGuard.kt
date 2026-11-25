package com.panomc.platform.backup

import com.panomc.platform.node.transfer.TransferSpaceService
import io.vertx.core.json.JsonObject
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Pano's disk ran (or would run) below its reserve while a Pano Backup job wrote its temporary files. [required] is
 * what was needed with the reserve when that was known up front, null when the disk filled mid-write. A job ends with
 * `NOT_ENOUGH_SPACE` and these as its details; its cleanup removes what it wrote.
 */
class PanoDiskFullException(val required: Long?, val free: Long) : IOException(
    if (required != null) "Not enough disk space on Pano: $required bytes needed, $free bytes free."
    else "Pano's disk is full: only $free bytes are left."
) {
    fun details(): JsonObject = JsonObject().put("free", free).also { json -> required?.let { json.put("required", it) } }

    companion object {
        const val CODE = "NOT_ENOUGH_SPACE"

        /** The [PanoDiskFullException] behind [error], however deep it was wrapped. */
        fun find(error: Throwable?): PanoDiskFullException? {
            var current = error
            var depth = 0

            while (current != null && depth++ < 16) {
                if (current is PanoDiskFullException) return current

                current = current.cause
            }

            return null
        }
    }
}

/**
 * Keeps [TransferSpaceService.RESERVE_BYTES] free on the disk of [directory] while a Pano Backup job writes there:
 * [require] before a file of known size, [wrap] around every temporary file it writes (re-checked every
 * [CHECK_EVERY] bytes). The same reserve the nodes keep, so a big download never leaves Pano without room for its
 * database and logs.
 */
class PanoDiskGuard(private val directory: File, private val usableSpace: (File) -> Long = { TransferSpaceService.freeOnPano(it) }) {
    private var sinceCheck = CHECK_EVERY

    fun free(): Long = usableSpace(directory)

    /** Refuses when [bytes] more would leave less than the reserve. */
    fun require(bytes: Long) {
        val free = free()
        val required = TransferSpaceService.required(bytes)

        if (free < required) throw PanoDiskFullException(required, free)
    }

    /** Throws when the disk is below the reserve now. */
    fun check() {
        val free = free()

        if (free < TransferSpaceService.RESERVE_BYTES) throw PanoDiskFullException(null, free)
    }

    fun written(bytes: Long) {
        sinceCheck += bytes

        if (sinceCheck >= CHECK_EVERY) {
            sinceCheck = 0

            check()
        }
    }

    /** [out] with every write counted (and the disk re-checked) before it happens. */
    fun wrap(out: OutputStream): OutputStream = object : FilterOutputStream(out) {
        override fun write(b: Int) {
            written(1)
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            written(len.toLong())
            out.write(b, off, len)
        }
    }

    companion object {
        const val CHECK_EVERY = 8L * 1024 * 1024

        /** A guard for the disk [file] is written to. */
        fun of(file: File) = PanoDiskGuard(file.absoluteFile.parentFile ?: file.absoluteFile)
    }
}
