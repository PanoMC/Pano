package com.panomc.node.console

import com.panomc.node.util.NodeLogger
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps a server's console, colours included, in `<server>/.pano-node/console.log` (§2.4.21 B).
 *
 * A Minecraft server's own `logs/latest.log` is written with every colour stripped, so a console
 * rebuilt from it after a restart is a grey one. This is the node's copy of what actually went
 * past: every line it put in the console — the process's output, its own `[Pano:user] > …` echo of
 * a command, what it read off an adopted server's log — one per line as
 * `<epochMillis>\t<text with its SGR codes>`.
 *
 * The console must never wait for a disk. [append] only queues the line; one shared background
 * thread writes the queues of every server in batches, so the stdout reader of a busy server costs
 * a queue insert per line. The queue is bounded, and when a disk is slower than a server is noisy
 * the lines that do not fit are dropped from the file — never from the console. A failing write is
 * logged once per server and otherwise ignored for the same reason.
 *
 * At [maxFileBytes] the file becomes `console.log.1`, the old `.1` becomes `.2`, and the old `.2`
 * is gone: two rotations of plain text, a few megabytes each, which is as far back as the
 * console's paging ever reaches anyway.
 */
class ConsoleLogWriter(
    private val serverDirectory: File,
    private val logger: NodeLogger,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val executor: Executor = SHARED
) {
    private val queue = ConcurrentLinkedQueue<String>()

    private val queued = AtomicInteger(0)

    private val scheduled = AtomicBoolean(false)

    private val warned = AtomicBoolean(false)

    /** Queues one console line for the file. Never blocks and never throws. */
    fun append(timestamp: Long, fileForm: String) {
        if (queued.incrementAndGet() > MAX_QUEUED_LINES) {
            queued.decrementAndGet()

            warnOnce("the console log of ${serverDirectory.name} is falling behind; lines are being skipped")

            return
        }

        // One record per line is the format's whole grammar, so a stray newline in a message the
        // node wrote itself is folded rather than allowed to forge the next record.
        queue.add("$timestamp\t${fileForm.replace('\n', ' ').replace('\r', ' ')}\n")

        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) {
            return
        }

        try {
            executor.execute { drain() }
        } catch (exception: Exception) {
            scheduled.set(false)

            warnOnce("could not schedule the console log of ${serverDirectory.name}: ${exception.message}")
        }
    }

    private fun drain() {
        try {
            write()
        } finally {
            scheduled.set(false)

            // A line that arrived between the last poll and the flag dropping would otherwise wait
            // for the next line to be written.
            if (queue.isNotEmpty()) {
                schedule()
            }
        }
    }

    private fun write() {
        val batch = ArrayList<String>()

        while (true) {
            val next = queue.poll() ?: break

            queued.decrementAndGet()

            batch.add(next)
        }

        // A server being deleted must not be brought back as a directory holding one log file.
        if (batch.isEmpty() || !serverDirectory.isDirectory) {
            return
        }

        try {
            val file = file(serverDirectory)

            file.parentFile.mkdirs()

            var length = if (file.isFile) file.length() else 0L
            var out: OutputStream = open(file)

            try {
                for (line in batch) {
                    val bytes = line.toByteArray(Charsets.UTF_8)

                    if (length > 0L && length + bytes.size > maxFileBytes) {
                        out.close()

                        rotate(file)

                        out = open(file)
                        length = 0L
                    }

                    out.write(bytes)

                    length += bytes.size
                }
            } finally {
                out.close()
            }
        } catch (exception: Exception) {
            warnOnce("could not write the console log of ${serverDirectory.name}: ${exception.message}")
        }
    }

    private fun open(file: File): OutputStream = BufferedOutputStream(FileOutputStream(file, true))

    /** `console.log` → `.1` → `.2`, the old `.2` dropped. */
    private fun rotate(file: File) {
        val first = File(file.parentFile, "${file.name}.1")
        val second = File(file.parentFile, "${file.name}.2")

        if (first.isFile) {
            Files.move(first.toPath(), second.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        Files.move(file.toPath(), first.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun warnOnce(message: String) {
        if (warned.compareAndSet(false, true)) {
            logger.warn("${message.replaceFirstChar { it.uppercase() }}. The console itself is unaffected.")
        }
    }

    companion object {
        /** The directory the node keeps its own per-server files in, next to `process.json`. */
        const val DIRECTORY = ".pano-node"

        const val FILE_NAME = "console.log"

        /** Where the file lives, relative to the server directory. */
        const val RELATIVE_PATH = "$DIRECTORY/$FILE_NAME"

        /** Size at which the file is rotated. */
        const val MAX_FILE_BYTES = 4L * 1024 * 1024

        /** Rotated files kept: `console.log.1` and `console.log.2`. */
        const val MAX_ROTATIONS = 2

        /** Lines that may wait for the disk before the file starts skipping them. */
        const val MAX_QUEUED_LINES = 10_000

        fun file(serverDirectory: File): File = File(serverDirectory, RELATIVE_PATH)

        /** `console.log.1`, `console.log.2` that exist, newest first. */
        fun rotations(serverDirectory: File): List<File> {
            val file = file(serverDirectory)

            return (1..MAX_ROTATIONS).map { File(file.parentFile, "${file.name}.$it") }.filter { it.isFile }
        }

        /** One thread for every server's file: writing is sequential, and nothing here is urgent. */
        private val SHARED: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pano-node-console-log").apply { isDaemon = true }
        }
    }
}
