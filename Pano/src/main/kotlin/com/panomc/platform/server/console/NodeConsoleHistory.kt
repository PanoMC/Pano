package com.panomc.platform.server.console

import com.panomc.platform.server.dto.ConsoleLineData
import com.panomc.platform.server.dto.ConsoleSpan
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * Reads a `CONSOLE_HISTORY` answer and folds it into what Pano already has.
 *
 * Pano only ever sees the lines a node pushed while somebody was watching, which is the wrong half
 * of the history for the one moment it matters: a server that crashed at four in the morning wrote
 * its reason into the node's own ring buffer and into nothing else here. The console endpoint can
 * ask for that buffer, and this is what turns the answer into lines the panel can render next to
 * the ones Pano already held.
 *
 * Removing the overlap is the point. The two windows meet — the source kept the same lines it
 * streamed — so the source's copy of a line Pano already holds is dropped and its own copy kept,
 * leaving one history instead of a console that prints its last few hundred lines twice.
 *
 * Pure, because the interesting cases (an overlap, a source that replayed its lines off disk with
 * a coarser clock, a buffer bigger than the limit) are all about the rule and none of them are
 * about a socket.
 */
object NodeConsoleHistory {
    /**
     * The lines out of one reply payload, normalised exactly like a pushed batch.
     *
     * `src` is set here and never read from the payload, for the same reason the push path sets
     * it: a sender that could label its own lines could claim to be the plugin stream.
     */
    fun parse(payload: JsonObject?, now: Long = System.currentTimeMillis()): List<ConsoleLineData> {
        val lines = payload?.getJsonArray("lines") ?: JsonArray()

        return lines
            .mapNotNull { it as? JsonObject }
            .take(MAX_LINES)
            .map { line ->
                sanitize(
                    line.getValue("t") as? Number,
                    line.getValue("l") as? String,
                    line.getValue("m") as? String,
                    ConsoleLineData.SRC_NODE,
                    now,
                    ConsoleSpans.parse(line.getValue("c"))
                )
            }
    }

    /**
     * The lines out of a plugin's `CONSOLE_HISTORY_RESULT`, already decoded by Gson.
     *
     * The same normalisation as a node's reply, down to the forced `src`: these lines come from
     * the plugin's own reader, which is the stream the console labels as the plugin's. Nulls are
     * handled rather than trusted — Gson fills a missing field with the data class default, but
     * an explicit JSON null lands in the field whatever its Kotlin type says.
     */
    fun parsePlugin(lines: List<ConsoleLineData>?, now: Long = System.currentTimeMillis()): List<ConsoleLineData> =
        (lines ?: emptyList())
            .filterNotNull()
            .take(MAX_LINES)
            .map { line -> sanitize(line.t, line.l, line.m, ConsoleLineData.SRC_PLUGIN, now, line.c) }

    /**
     * One line as Pano will hold it: a known level, a bounded message, a clock that ran, and only
     * the colour spans that are valid for that message (§2.4.21 D). Also what a deep search's
     * lines go through ([ConsoleDeepSearch]), so a match is held to exactly the same rules.
     */
    internal fun sanitize(
        timestamp: Number?,
        level: String?,
        message: String?,
        src: String,
        now: Long,
        spans: List<ConsoleSpan>? = null
    ): ConsoleLineData {
        val normalised = level?.uppercase()
        val time = timestamp?.toLong()
        val text = message.orEmpty().take(ConsoleLineData.MAX_MESSAGE_LENGTH)

        return ConsoleLineData(
            t = if (time == null || time <= 0) now else time,
            l = if (normalised in ConsoleLineData.KNOWN_LEVELS) normalised!! else ConsoleLineData.UNKNOWN_LEVEL,
            m = text,
            src = src,
            c = ConsoleSpans.validate(spans, text)
        )
    }

    /**
     * [local] and [fromSource] as one history, oldest first, capped at [limit] newest lines.
     *
     * The source's page is the older half and Pano's buffer the newer one, so the join is where
     * the source's last lines are Pano's first: the longest such run is cut off the source and
     * Pano's copies of those lines are the ones kept, since those are what the panel may already
     * have rendered and they carry the millisecond Pano saw rather than the second a log file
     * recorded. Longest and not first, because a server that prints the same line every tick
     * would otherwise match on one line and drop nothing.
     *
     * Matching on the text alone is what makes it work at all. A source that lost its ring buffer
     * — a node restarted, a server restarted — replays the same lines out of its log file, where
     * every timestamp has been rounded to the second and none of them is the millisecond Pano
     * recorded. Comparing timestamps there finds no overlap and shows the operator everything
     * twice, which is the bug this rule replaced.
     */
    fun merge(local: List<ConsoleLineData>, fromSource: List<ConsoleLineData>, limit: Int): List<ConsoleLineData> {
        if (limit <= 0) {
            return emptyList()
        }

        if (fromSource.isEmpty()) {
            return local.takeLast(limit)
        }

        if (local.isEmpty()) {
            return fromSource.takeLast(limit)
        }

        val max = minOf(fromSource.size, local.size, MAX_OVERLAP)

        var overlap = 0

        for (k in max downTo 1) {
            if (matches(fromSource, local, k)) {
                overlap = k

                break
            }
        }

        return (fromSource.subList(0, fromSource.size - overlap) + local).takeLast(limit)
    }

    /** Whether the last [k] source lines are the first [k] lines Pano holds, by text alone. */
    private fun matches(fromSource: List<ConsoleLineData>, local: List<ConsoleLineData>, k: Int): Boolean {
        val offset = fromSource.size - k

        for (i in 0 until k) {
            if (fromSource[offset + i].m != local[i].m) {
                return false
            }
        }

        return true
    }

    /** Most lines taken out of one reply, mirroring the ceiling on a pushed batch. */
    private const val MAX_LINES = 500

    /**
     * How far into Pano's buffer an overlap is looked for.
     *
     * A cap rather than the whole buffer because the search is quadratic in it, and because a
     * source only ever replays what its own ring held — beyond that there is nothing to match.
     */
    const val MAX_OVERLAP = 500
}
