package com.panomc.platform.server.console

import com.panomc.platform.server.dto.ConsoleLineData

/**
 * The console's Find, on Pano's side of the wire (§2.4.20).
 *
 * The node and the plugin search their own log files; this is the same question asked of what Pano
 * holds, and the same rules on purpose — trimmed, capped at [MAX_QUERY_LENGTH], a case-insensitive
 * plain substring of the line's text — so a search answers the same way whichever side served it.
 *
 * It has two jobs. When neither source can answer, Pano's own ring buffer is searched instead,
 * with [page]. And whatever a source *does* send back is filtered again with [filter], because a
 * node or plugin that predates the field ignores it and sends the plain page: filtering an already
 * filtered page changes nothing, and filtering an unfiltered one is what keeps a line that does not
 * match from ever being shown as a match.
 */
object ConsoleSearch {
    /** Longest query honoured, the same cap the node and the plugin apply. */
    const val MAX_QUERY_LENGTH = 200

    /**
     * What a search looks for, or null when [query] is not a search at all.
     *
     * Blank is null so that an empty Find box is the plain console, exactly as before.
     */
    fun needle(query: String?): String? = query?.trim()?.take(MAX_QUERY_LENGTH)?.takeIf { it.isNotEmpty() }

    /** Whether [line] contains [needle], ignoring case — a substring, never a pattern. */
    fun matches(line: ConsoleLineData, needle: String): Boolean = line.m.contains(needle, ignoreCase = true)

    /** Only the lines of [lines] that match, in their order. */
    fun filter(lines: List<ConsoleLineData>, needle: String): List<ConsoleLineData> =
        lines.filter { matches(it, needle) }

    /**
     * One page of matches out of [lines] (oldest first), found the way the node finds them.
     *
     * Walked newest to oldest, [skip] steps over matches the panel already shows, [limit] is how
     * many come back, and the walk stops as soon as the page is full, so only the page is ever
     * kept. [Page.hasMore] is true when the page filled with lines still unscanned below it — no
     * look ahead to check that one of them matches.
     */
    fun page(lines: List<ConsoleLineData>, limit: Int, skip: Int, needle: String): Page {
        if (limit <= 0) {
            return Page(emptyList(), false)
        }

        val found = ArrayDeque<ConsoleLineData>()

        var skipped = 0
        var index = lines.lastIndex

        while (index >= 0 && found.size < limit) {
            val line = lines[index]

            index--

            if (!matches(line, needle)) {
                continue
            }

            if (skipped < skip) {
                skipped++

                continue
            }

            found.addFirst(line)
        }

        return Page(found.toList(), found.size >= limit && index >= 0)
    }

    /** One page of matches, and whether asking for the next one is worth it. */
    data class Page(val lines: List<ConsoleLineData>, val hasMore: Boolean)
}
