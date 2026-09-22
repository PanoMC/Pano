package com.panomc.node.util

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The daemon's own log.
 *
 * Deliberately not SLF4J or log4j: the node is a single fat jar that has to start on a host where
 * nothing has been configured, and every line it writes goes to stdout so whoever supervises it
 * (systemd, launchd, a Windows service wrapper or Pano itself redirecting into logs/pano-node.log)
 * captures it without a logging config file existing at all.
 *
 * A Pano Agent's worker is the exception (SM-74, see [agent]): its stdout is the Minecraft console
 * in the admin's terminal, so its log goes to `<data>/agent.log` instead, and only what the admin
 * has to see reaches the terminal — warnings and errors on stderr, and the few lifecycle lines
 * [notice] is for — each behind a `[Pano Agent]` prefix.
 */
class NodeLogger(
    private val name: String,
    private val out: PrintStream = System.out,
    /** Where the log goes instead of [out], for an agent's worker. */
    private val file: RotatingLogFile? = null,
    /** Where warnings, errors and notices are also shown, prefixed, for an agent's worker. */
    private val terminal: PrintStream? = null,
    private val terminalErrors: PrintStream? = null
) {
    fun info(message: String) = write("INFO", message)

    fun warn(message: String) {
        write("WARN", message)

        show(terminalErrors, message)
    }

    fun error(message: String, cause: Throwable? = null) {
        write("ERROR", message)

        cause?.let { writeRaw(it.stackTraceToString()) }

        show(terminalErrors, message)
    }

    /**
     * A moment the person running the daemon should hear about even when the log is a file: paired,
     * connected, lost the connection, linked a server, updating, removed. An ordinary node logs it
     * like [info]; an agent's worker also prints it to the terminal.
     */
    fun notice(message: String) {
        write("INFO", message)

        show(terminal, message)
    }

    private fun write(level: String, message: String) {
        writeRaw("${FORMATTER.format(LocalDateTime.now())} [$level] [$name] $message")
    }

    private fun writeRaw(line: String) {
        val sink = file

        if (sink != null) {
            sink.append(line)

            return
        }

        out.println(line)
        out.flush()
    }

    private fun show(stream: PrintStream?, message: String) {
        stream ?: return

        stream.println("$AGENT_PREFIX $message")
        stream.flush()
    }

    companion object {
        private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** What every line an agent shows in the terminal starts with. */
        const val AGENT_PREFIX = "[Pano Agent]"

        /** The agent worker's log file, in its data directory. */
        const val AGENT_LOG_FILE = "agent.log"

        /** A Pano Agent worker's logger: the log in `<dataDir>/agent.log`, the essentials in the terminal. */
        fun agent(name: String, dataDir: File): NodeLogger = NodeLogger(
            name = name,
            file = RotatingLogFile(File(dataDir, AGENT_LOG_FILE)),
            terminal = System.out,
            terminalErrors = System.err
        )
    }
}

/**
 * A log file that never grows past [maxBytes]: when it would, it becomes `<name>.1`, the previous
 * `.1` becomes `.2`, and so on up to [keep]. The agent runs for as long as the server does, in the
 * server's own folder, so an unbounded log is simply a disk that fills up one day.
 */
class RotatingLogFile(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val keep: Int = DEFAULT_KEEP
) {
    private var stream: FileOutputStream? = null

    private var size = 0L

    @Synchronized
    fun append(line: String) {
        try {
            val bytes = "$line\n".toByteArray(Charsets.UTF_8)

            val current = stream ?: open()

            if (size > 0 && size + bytes.size > maxBytes) {
                rotate()

                (stream ?: open()).write(bytes)
            } else {
                current.write(bytes)
            }

            size += bytes.size

            stream?.flush()
        } catch (_: Exception) {
            // A log that cannot be written must never take the daemon down with it.
        }
    }

    private fun open(): FileOutputStream {
        file.parentFile?.mkdirs()

        size = if (file.isFile) file.length() else 0L

        return FileOutputStream(file, true).also { stream = it }
    }

    private fun rotate() {
        try {
            stream?.close()
        } catch (_: Exception) {
        }

        stream = null

        File(file.path + ".$keep").delete()

        for (index in keep - 1 downTo 1) {
            File(file.path + ".$index").takeIf { it.exists() }?.renameTo(File(file.path + ".${index + 1}"))
        }

        file.renameTo(File(file.path + ".1"))

        size = 0L
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
        const val DEFAULT_KEEP = 3
    }
}
