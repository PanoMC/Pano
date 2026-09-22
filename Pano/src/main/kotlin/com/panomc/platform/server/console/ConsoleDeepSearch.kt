package com.panomc.platform.server.console

import com.panomc.platform.error.BadCursor
import com.panomc.platform.error.BadQuery
import com.panomc.platform.error.FeatureUnavailable
import com.panomc.platform.error.ReadFailed
import com.panomc.platform.model.Error
import com.panomc.platform.server.dto.ConsoleLineData
import com.panomc.platform.server.event.request.ConsoleSearchResultEventRequest
import com.panomc.platform.server.feature.ServerFeature
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * Pano's side of the console's deep search (`CONSOLE_SEARCH`).
 *
 * The node or the plugin does the searching — they hold the log files — and Pano only relays: it
 * checks what the panel asked before it goes down the socket and what came back before it goes to
 * the panel. Both halves are here and pure, because the rules (what a cursor may look like, which
 * limit is honoured, what a source's page is allowed to contain) are the whole of this job and
 * none of them are about a socket.
 *
 * A source's page is held to the rules of a history page: every line sanitised by
 * [NodeConsoleHistory.sanitize] with the `src` forced by who answered, at most [MAX_LINES] of
 * them, and each keeps the `f` naming the file it came from. The cursor stays opaque — Pano never
 * decodes it, only refuses one that could not have come from a source at all, so a client-held
 * string cannot be used to smuggle anything bigger or stranger down to a node.
 */
object ConsoleDeepSearch {
    /** Matches one page holds when the panel did not say. */
    const val DEFAULT_LIMIT = 200

    /** Most matches one page may hold, the same ceiling the node and the plugin apply. */
    const val MAX_LIMIT = 1000

    /** How long a source may read for one page. Well inside the request timeout. */
    const val BUDGET_MS = 1500

    /** Longest cursor relayed; a real one is a few dozen characters. */
    const val MAX_CURSOR_LENGTH = 1024

    /** Most lines taken out of one reply, whatever the source sent. */
    const val MAX_LINES = MAX_LIMIT

    /** Longest file label kept; a log file's name is never anywhere near it. */
    const val MAX_FILE_LENGTH = 255

    /** Base64url, padded or not: every cursor a source writes, and nothing else. */
    private val CURSOR = Regex("^[A-Za-z0-9_-]+={0,2}$")

    /** One match, and the log file it was found in. */
    data class Match(val line: ConsoleLineData, val file: String)

    /**
     * One page of a search as the panel receives it.
     *
     * [cursor] is null exactly when [done] is true: a source that claims to be unfinished without
     * saying where to resume is treated as finished, so the panel can never be sent round in a
     * loop by a confused peer.
     */
    data class Page(
        val lines: List<Match>,
        val cursor: String?,
        val done: Boolean,
        val scannedFiles: Int,
        val totalFiles: Int,
        val scannedBytes: Long,
        val capped: Boolean
    ) {
        /** The response body: the source's payload, verbatim in shape, with `src` on every line. */
        fun toMap(query: String): Map<String, Any?> = mapOf(
            "ok" to true,
            "query" to query,
            "lines" to lines.map { it.line.toJsonObject().put("f", it.file) },
            "cursor" to cursor,
            "done" to done,
            "scannedFiles" to scannedFiles,
            "totalFiles" to totalFiles,
            "scannedBytes" to scannedBytes,
            "capped" to capped
        )
    }

    /** The limit the panel asked for, clamped to what a source will honour. */
    fun limit(requested: Int?): Int = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    /** What to search for, normalised as every console search is, or [BadQuery] when blank. */
    fun query(raw: String?): String = ConsoleSearch.needle(raw) ?: throw BadQuery()

    /**
     * The panel's cursor, or null to start at the newest file.
     *
     * Blank is a first page. Anything that is not the shape a source writes is refused here with
     * [BadCursor] — the same answer the source would give, one socket round trip sooner.
     */
    fun cursor(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }

        if (raw.length > MAX_CURSOR_LENGTH || !CURSOR.matches(raw)) {
            throw BadCursor()
        }

        return raw
    }

    /** A node's `FILE_RESULT` payload as a page, or the error it stands for. */
    fun fromNode(payload: JsonObject, now: Long = System.currentTimeMillis()): Page {
        if (!payload.getBoolean("ok", false)) {
            throw failure(payload.getValue("error") as? String, disabled = false)
        }

        val lines = (payload.getValue("lines") as? JsonArray ?: JsonArray())
            .mapNotNull { it as? JsonObject }
            .take(MAX_LINES)
            .map { line ->
                Match(
                    NodeConsoleHistory.sanitize(
                        line.getValue("t") as? Number,
                        line.getValue("l") as? String,
                        line.getValue("m") as? String,
                        ConsoleLineData.SRC_NODE,
                        now,
                        ConsoleSpans.parse(line.getValue("c"))
                    ),
                    file(line.getValue("f") as? String)
                )
            }

        return page(
            lines,
            payload.getValue("cursor") as? String,
            payload.getValue("done") as? Boolean,
            payload.getValue("scannedFiles") as? Number,
            payload.getValue("totalFiles") as? Number,
            payload.getValue("scannedBytes") as? Number,
            payload.getValue("capped") as? Boolean
        )
    }

    /** A plugin's `CONSOLE_SEARCH_RESULT` as a page, or the error it stands for. */
    fun fromPlugin(reply: ConsoleSearchResultEventRequest, now: Long = System.currentTimeMillis()): Page {
        if (reply.ok != true || reply.disabled == true) {
            throw failure(reply.error, disabled = reply.disabled == true)
        }

        val lines = (reply.lines ?: emptyList())
            .filterNotNull()
            .take(MAX_LINES)
            .map { line ->
                Match(
                    NodeConsoleHistory.sanitize(line.t, line.l, line.m, ConsoleLineData.SRC_PLUGIN, now, line.c),
                    file(line.f)
                )
            }

        return page(
            lines,
            reply.cursor,
            reply.done,
            reply.scannedFiles,
            reply.totalFiles,
            reply.scannedBytes,
            reply.capped
        )
    }

    /**
     * The error a source's refusal becomes.
     *
     * `BAD_QUERY`, `BAD_CURSOR` and `READ_FAILED` keep their codes, because the panel acts on each
     * of them differently — `BAD_CURSOR` in particular restarts the search. A disabled console is
     * what the history endpoint shows as "no source" and becomes the same [FeatureUnavailable] the
     * panel falls back to its windowed search on; so does anything else, an unknown server or a
     * code from a newer peer.
     */
    fun failure(code: String?, disabled: Boolean): Error = when {
        disabled -> unavailable("DISABLED")
        code == "BAD_QUERY" -> BadQuery()
        code == "BAD_CURSOR" -> BadCursor()
        code == "READ_FAILED" -> ReadFailed()
        else -> unavailable(code)
    }

    /** "Nothing can search this server's history right now", with the source's reason if any. */
    fun unavailable(reason: String? = null): FeatureUnavailable = FeatureUnavailable(
        extras = mapOf("feature" to ServerFeature.CONSOLE_HISTORY.id) +
            (reason?.take(64)?.let { mapOf("reason" to it) } ?: emptyMap())
    )

    private fun page(
        lines: List<Match>,
        cursor: String?,
        done: Boolean?,
        scannedFiles: Number?,
        totalFiles: Number?,
        scannedBytes: Number?,
        capped: Boolean?
    ): Page {
        val next = cursor?.takeIf { it.isNotEmpty() && it.length <= MAX_CURSOR_LENGTH && CURSOR.matches(it) }
        val finished = done != false || next == null

        val total = totalFiles?.toInt()?.coerceAtLeast(0) ?: 0

        return Page(
            lines = lines,
            cursor = if (finished) null else next,
            done = finished,
            scannedFiles = (scannedFiles?.toInt()?.coerceAtLeast(0) ?: 0).coerceAtMost(maxOf(total, 0)),
            totalFiles = total,
            scannedBytes = scannedBytes?.toLong()?.coerceAtLeast(0L) ?: 0L,
            capped = capped == true
        )
    }

    /**
     * A source's file label, as text the panel can show and nothing more: control characters out,
     * capped at [MAX_FILE_LENGTH]. Missing is an empty string, never null, so every line has an `f`.
     */
    fun file(raw: String?): String =
        (raw ?: "").filter { it >= ' ' && it != '\u007F' }.take(MAX_FILE_LENGTH)
}
