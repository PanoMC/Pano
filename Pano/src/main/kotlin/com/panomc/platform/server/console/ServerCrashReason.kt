package com.panomc.platform.server.console

import com.panomc.platform.server.dto.ConsoleLineData

/**
 * The one sentence that says why a server died, pulled out of its last console lines.
 *
 * A crash notification that says "\"Lobby\" stopped on its own (exit code 1)" tells an admin
 * something they can already see. The line above it — `java.lang.UnsupportedClassVersionError:
 * com/velocitypowered/proxy/Velocity has been compiled by a more recent version of the Java
 * Runtime` — is the whole answer, and it costs nothing to carry.
 *
 * The node sends this with the crash itself; this is the fallback for a node too old to, which
 * reads the same thing out of Pano's own buffer.
 *
 * The *first* error rather than the last, because a JVM prints the cause and then unwinds: the top
 * of the failure is the sentence somebody needs and the bottom is a stack frame.
 */
object ServerCrashReason {
    /** How much of a line is worth putting in a notification. */
    const val MAX_LENGTH = 200

    /**
     * Words that mark a line as the failure even when the process wrote it to stdout.
     *
     * The JVM's own start-up refusals are listed by name: HotSpot prints them to stdout, in prose,
     * with no colon after "Error", and a bad `-Xmx` is the most ordinary way to kill a server.
     */
    private val MARKERS = listOf(
        "Exception",
        "Error:",
        "error:",
        "Error occurred during initialization",
        "Could not create the Java Virtual Machine",
        "Unrecognized option",
        "Unrecognized VM option",
        "Invalid maximum heap size",
        "Invalid initial heap size",
        "Could not reserve enough space",
        "Too small maximum heap"
    )

    /** The node's own exit summary: the exit code said twice, never a reason. */
    private const val EXIT_SUMMARY_PREFIX = "Server process exited with code"

    /** The reason [lines] carry, or null when none of them looks like one. */
    fun of(lines: List<ConsoleLineData>): String? = lines
        .firstOrNull { line ->
            !line.m.startsWith(EXIT_SUMMARY_PREFIX) && (line.l == ERROR_LEVEL || MARKERS.any { line.m.contains(it) })
        }
        ?.m
        ?.let { clean(it) }

    /** Trims and caps a reason from any source, so a node cannot push a notification-sized wall. */
    fun clean(reason: String?): String? = reason
        ?.replace('\n', ' ')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(MAX_LENGTH)

    private const val ERROR_LEVEL = "ERROR"
}
