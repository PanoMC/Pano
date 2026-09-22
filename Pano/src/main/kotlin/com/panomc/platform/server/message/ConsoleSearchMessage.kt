package com.panomc.platform.server.message

import com.panomc.platform.server.ServerRequestMessage

/**
 * Asks a connected plugin for one page of a search through every log file its server has
 * (`CONSOLE_SEARCH`).
 *
 * The plugin's half of the node's deep search, with the same fields minus the server: a linked
 * server has no node, so its plugin is the only thing on that machine that can read the files.
 * [cursor] is the one the previous page ended on, or null to start at the newest file.
 *
 * The answer comes back as `CONSOLE_SEARCH_RESULT` carrying the same `eventId`. A plugin too old
 * to know the message never answers, and the timeout is what tells the panel to fall back to the
 * windowed search.
 */
class ConsoleSearchMessage(
    val query: String,
    val cursor: String?,
    val limit: Int,
    val budgetMs: Int
) : ServerRequestMessage()
