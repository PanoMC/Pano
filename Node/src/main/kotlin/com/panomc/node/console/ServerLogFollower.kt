package com.panomc.node.console

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDate
import java.time.ZoneId

/**
 * The live console of a server whose pipes this daemon does not hold (SM-51, §2.4.16).
 *
 * An adopted process was started by a daemon that is gone, so its stdout went to a pipe that died
 * with it. What is left is the file the server writes anyway, and tailing that from its current
 * end is what keeps a panel console live for a server nobody owns the handles to. History is
 * already covered by [ServerLogTail]; this is only the part that arrives from now on, which is why
 * it opens at the end of the file rather than at the beginning.
 *
 * Everything here is bounded, because a log is an untrusted file a misbehaving plugin can grow
 * without limit: at most [MAX_READ_BYTES] per poll, at most [MAX_LINE_LENGTH] held for one line,
 * and a line longer than that is truncated with the rest of it dropped rather than buffered.
 * Rotation is handled by noticing that the file is a different one or has become shorter, and
 * reading it again from the top -- a server that rolls its log at midnight must not go silent.
 */
class ServerLogFollower(
    private val serverDirectory: File,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val onLines: (List<ConsoleLine>) -> Unit
) {
    private val lock = Any()

    private var position = 0L

    private var fileKey: Any? = null

    private val carry = StringBuilder()

    /** Whether the rest of an over-long line is being thrown away until the next newline. */
    private var dropping = false

    @Volatile
    private var running = false

    @Volatile
    private var thread: Thread? = null

    /** Starts at the end of the log as it is now, so nothing already in the history is replayed. */
    fun open() {
        synchronized(lock) {
            val file = latest()

            position = if (file.isFile) file.length() else 0L
            fileKey = keyOf(file)

            carry.setLength(0)

            dropping = false
        }
    }

    /**
     * Follows the log on a daemon thread until [alive] says the process is gone.
     *
     * The thread is a daemon one because it must never be the reason a node cannot exit, and it
     * swallows everything: a disk that went away is a console that stops updating, not a node that
     * takes a stack trace to stdout every half second.
     */
    fun start(name: String, alive: () -> Boolean) {
        open()

        running = true

        val follower = Thread({
            try {
                while (running) {
                    if (!alive()) {
                        break
                    }

                    drain()

                    try {
                        Thread.sleep(POLL_INTERVAL_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()

                        break
                    }
                }

                // Whatever the server wrote on its way out, after the last poll and before the
                // handle reported the exit: that is usually the only explanation there is.
                drain()
            } catch (_: Throwable) {
                // Nothing may escape this thread.
            }
        }, name).apply { isDaemon = true }

        thread = follower

        follower.start()
    }

    fun stop() {
        running = false

        thread?.interrupt()
        thread = null
    }

    /** Reads whatever has been appended since the last call. Safe to call from any thread. */
    fun drain() {
        synchronized(lock) {
            try {
                poll()
            } catch (_: Throwable) {
                // A log that cannot be read right now is read again on the next poll.
            }
        }
    }

    private fun poll() {
        val file = latest()

        if (!file.isFile) {
            // The file is gone; whatever replaces it is a new file to be read from its start.
            position = 0L
            fileKey = null

            carry.setLength(0)

            dropping = false

            return
        }

        val key = keyOf(file)
        val length = file.length()

        val rotated = (key != null && fileKey != null && key != fileKey) || length < position

        if (rotated) {
            position = 0L

            carry.setLength(0)

            dropping = false
        }

        fileKey = key ?: fileKey

        if (length <= position) {
            return
        }

        val wanted = minOf(length - position, MAX_READ_BYTES).toInt()
        val bytes = ByteArray(wanted)

        var read = 0

        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            channel.position(position)

            while (read < wanted) {
                val count = channel.read(ByteBuffer.wrap(bytes, read, wanted - read))

                if (count <= 0) {
                    break
                }

                read += count
            }
        }

        if (read <= 0) {
            return
        }

        position += read

        val lines = extract(decode(bytes, read))

        if (lines.isEmpty()) {
            return
        }

        // The same parser the history reader uses, so a line looks identical whether it arrived
        // through a pipe, through a page of history or through this.
        onLines(ServerLogTail.parse(lines, LocalDate.now(zone), zone))
    }

    /** Complete lines out of [text], keeping any unfinished tail for the next read. */
    private fun extract(text: String): List<String> {
        val lines = mutableListOf<String>()

        text.forEach { char ->
            if (char == '\n') {
                val line = carry.toString().trimEnd('\r')

                carry.setLength(0)

                dropping = false

                if (line.isNotBlank()) {
                    lines.add(line)
                }

                return@forEach
            }

            if (dropping) {
                return@forEach
            }

            if (carry.length >= MAX_LINE_LENGTH) {
                dropping = true

                return@forEach
            }

            carry.append(char)
        }

        return lines
    }

    private fun latest(): File = File(File(serverDirectory, "logs"), LATEST_LOG)

    /**
     * What tells one `latest.log` from the next one.
     *
     * The inode where the filesystem has one, so a rotation that replaces the file with an equally
     * long one is still noticed; null on a filesystem without file keys, where the length check
     * is what is left.
     */
    private fun keyOf(file: File): Any? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
    } catch (_: Exception) {
        null
    }

    /** UTF-8 with malformed bytes replaced: a read that stops mid-character must not throw. */
    private fun decode(bytes: ByteArray, length: Int): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(ByteBuffer.wrap(bytes, 0, length))
        .toString()

    companion object {
        const val LATEST_LOG = "latest.log"

        /** How often the file is checked. The console contract flushes every 250 ms anyway. */
        const val POLL_INTERVAL_MILLIS = 500L

        /** How much of the file may be read in one poll. A quarter megabyte is ~2000 log lines. */
        const val MAX_READ_BYTES = 256L * 1024

        /** Longest line kept, matching the protocol's per-line cap so nothing is cut twice. */
        const val MAX_LINE_LENGTH = ConsoleLineParser.MAX_MESSAGE_LENGTH
    }
}
