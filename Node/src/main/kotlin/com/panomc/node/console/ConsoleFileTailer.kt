package com.panomc.node.console

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reads a detached server's `console.out` as it grows, by byte offset (SM-62, §2.4.27).
 *
 * What a pipe used to deliver, a file delivers now: every complete line past the offset, in order,
 * exactly once. The offset is the whole of the state, which is what lets one daemon stop reading
 * and another carry on from the same byte: [committedOffset] is the start of the first line not yet
 * delivered — a line the server is still in the middle of writing is held back rather than counted,
 * so a restart between its two halves neither loses nor splits it.
 *
 * The file is kept from growing forever with `copytruncate` semantics: once everything up to
 * [rotateAtBytes] has been read, it is copied to its `.1` sibling and truncated in place. The
 * launcher appends with `O_APPEND`, so its next write lands at the new end rather than at the old
 * offset, and nothing has to reopen anything. A file that shrank for any other reason (someone
 * truncated it) is read again from the start.
 *
 * Not thread-safe by design; [poll] is synchronized and every other entry point goes through it.
 */
class ConsoleFileTailer(
    private val file: File,
    private val rotated: File,
    startOffset: Long,
    private val rotateAtBytes: Long = ROTATE_AT_BYTES,
    private val onLines: (List<String>) -> Unit
) {
    /** Bytes of the file consumed so far, including a line still being written. */
    @Volatile
    private var offset: Long = startOffset.coerceAtLeast(0L)

    /** The unfinished last line, bytes rather than text so a character split across reads survives. */
    private val partial = ByteArrayOutputStream()

    @Volatile
    private var running = false

    private var thread: Thread? = null

    /** Where a reader taking over should start: the first byte of the first undelivered line. */
    val committedOffset: Long
        @Synchronized get() = offset - partial.size()

    /**
     * Reads everything new and delivers the complete lines. Returns how many were delivered.
     *
     * Reads in bounded chunks until it has caught up, so a daemon re-attaching to a server that
     * wrote a lot while it was away delivers it all without holding it all at once.
     */
    @Synchronized
    fun poll(): Int {
        var delivered = 0

        while (true) {
            val size = if (file.isFile) file.length() else 0L

            if (size < offset) {
                // Shrunk underneath us: not our rotation (that resets the offset itself), so the
                // only safe reading is from the start.
                offset = 0L
                partial.reset()
            }

            if (size <= offset) {
                break
            }

            val lines = readChunk(size)

            if (lines.isNotEmpty()) {
                onLines(lines)

                delivered += lines.size
            }
        }

        rotateIfDue()

        return delivered
    }

    /**
     * One last read at the end of a run: everything new, and the unfinished line as a line of its
     * own — the server is gone and nothing will ever complete it.
     */
    @Synchronized
    fun drain(): Int {
        var delivered = poll()

        if (partial.size() > 0) {
            onLines(listOf(decode(partial.toByteArray())))

            partial.reset()

            delivered++
        }

        return delivered
    }

    /** Polls every [intervalMillis] on a daemon thread until [stop]. */
    fun start(threadName: String, intervalMillis: Long = POLL_INTERVAL_MILLIS) {
        if (running) {
            return
        }

        running = true

        thread = Thread({
            while (running) {
                try {
                    poll()
                } catch (_: Exception) {
                    // A file briefly gone or unreadable is the next poll's problem, not a reason
                    // to stop reading the console for good.
                }

                try {
                    Thread.sleep(intervalMillis)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, threadName).apply {
            isDaemon = true
            start()
        }
    }

    /** Stops the polling thread; [committedOffset] stays valid for whoever carries on. */
    fun stop() {
        running = false

        thread?.interrupt()
        thread = null
    }

    private fun readChunk(size: Long): List<String> {
        val length = minOf(size - offset, CHUNK_BYTES.toLong()).toInt()
        val bytes = ByteArray(length)

        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            input.readFully(bytes)
        }

        offset += length

        val lines = mutableListOf<String>()

        for (byte in bytes) {
            if (byte == NEWLINE) {
                lines.add(decode(partial.toByteArray()))

                partial.reset()
            } else if (partial.size() < MAX_LINE_BYTES) {
                partial.write(byte.toInt())
            }
        }

        return lines
    }

    /**
     * `copytruncate`: copy what has been read, then empty the file in place.
     *
     * Only when everything has been delivered and nothing has been appended since the last read —
     * the size is checked again right before the truncate, so the window in which a line could be
     * lost is the one system call between the check and the truncate, not a whole poll interval.
     */
    private fun rotateIfDue() {
        if (offset < rotateAtBytes || partial.size() > 0 || !file.isFile) {
            return
        }

        try {
            RandomAccessFile(file, "rw").use { access ->
                val channel = access.channel

                if (channel.size() != offset) {
                    return
                }

                Files.copy(file.toPath(), rotated.toPath(), StandardCopyOption.REPLACE_EXISTING)

                if (channel.size() == offset) {
                    channel.truncate(0L)

                    offset = 0L
                }
            }
        } catch (_: Exception) {
            // Tried again on the next poll; a file that cannot be rotated just grows a little more.
        }
    }

    private fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).trimEnd('\r')

    companion object {
        /** Size at which `console.out` is rotated. */
        const val ROTATE_AT_BYTES = 16L * 1024L * 1024L

        /** How often a live server's output is read: as fast as the old pipe felt. */
        const val POLL_INTERVAL_MILLIS = 200L

        /** Most bytes read in one go. */
        const val CHUNK_BYTES = 1024 * 1024

        /** Longest line held; the rest is dropped — the console caps a line far below this anyway. */
        const val MAX_LINE_BYTES = 64 * 1024

        private const val NEWLINE: Byte = '\n'.code.toByte()

        /**
         * The complete lines of [file] from [fromOffset], at most [maxLines] of them (the newest),
         * for a server that exited while no daemon was reading: what it said on its way out.
         */
        fun readLines(file: File, fromOffset: Long, maxLines: Int = REPLAY_MAX_LINES): List<String> {
            val collected = ArrayDeque<String>()

            val tailer = ConsoleFileTailer(file, File(file.parentFile, "${file.name}.unused"), fromOffset, Long.MAX_VALUE) { lines ->
                lines.forEach { line ->
                    collected.addLast(line)

                    while (collected.size > maxLines) {
                        collected.removeFirst()
                    }
                }
            }

            tailer.drain()

            return collected.toList()
        }

        /** Lines replayed for a server that exited while away. */
        const val REPLAY_MAX_LINES = 5_000
    }
}
