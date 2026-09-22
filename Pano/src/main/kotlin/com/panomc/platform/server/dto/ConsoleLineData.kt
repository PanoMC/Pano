package com.panomc.platform.server.dto

import com.google.gson.annotations.JsonAdapter
import com.panomc.platform.server.console.ConsoleSpans
import io.vertx.core.json.JsonObject

/**
 * One console line as it travels on the wire, in the compact shape the plugin sends.
 *
 * Field names are part of the protocol and stay short because a busy server streams hundreds of
 * these per second: `t` is the epoch millisecond timestamp, `l` the log level and `m` the raw
 * message text. The text is stored and forwarded exactly as received (ANSI already stripped by the
 * plugin); Pano never renders or escapes it, that is the panel's job.
 *
 * [src] is added by Pano, never read from the payload: a managed server has two streams into the
 * same buffer (the plugin's log appender and the node's stdout pipe) and the panel has to be able
 * to tell them apart. Letting a sender pick its own label would let a plugin claim to be the node.
 */
data class ConsoleLineData(
    val t: Long = 0,
    val l: String = UNKNOWN_LEVEL,
    val m: String = "",
    val src: String = SRC_PLUGIN,
    /**
     * Colour spans over [m] (§2.4.21), JSON `c`, or null when the line has none.
     *
     * Decoded leniently — a malformed `c` becomes null rather than failing the batch it came in —
     * and re-validated against the final [m] with [ConsoleSpans.validate] before Pano keeps or
     * relays the line. A line from a peer that predates colour simply has none.
     */
    @field:JsonAdapter(ConsoleSpans.GsonAdapter::class)
    val c: List<ConsoleSpan>? = null
) {
    /**
     * The line as the panel receives it, over the realtime hub and from the history endpoint alike:
     * `c` only when there is something in it, so a plain line is byte for byte what it always was.
     */
    fun toJsonObject(): JsonObject {
        val json = JsonObject()
            .put("t", t)
            .put("l", l)
            .put("m", m)
            .put("src", src)

        c?.takeIf { it.isNotEmpty() }?.let { spans -> json.put("c", ConsoleSpans.toJson(spans)) }

        return json
    }

    companion object {
        const val UNKNOWN_LEVEL = "INFO"

        /** Line came from the Pano plugin running inside the server. */
        const val SRC_PLUGIN = "plugin"

        /** Line came from the node reading the process's stdout/stderr. */
        const val SRC_NODE = "node"

        /** Levels the plugin is allowed to send; anything else is normalised to [UNKNOWN_LEVEL]. */
        val KNOWN_LEVELS = setOf("INFO", "WARN", "ERROR", "DEBUG", "TRACE")

        /** Hard cap per line, mirroring the plugin-side cap so one huge line cannot blow up memory. */
        const val MAX_MESSAGE_LENGTH = 4 * 1024
    }
}
