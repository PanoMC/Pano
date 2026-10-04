package com.panomc.platform.node.message

import com.panomc.platform.node.NodeRequestMessage

/**
 * Asks a node for one page of a search through every log file a server has (`CONSOLE_SEARCH`).
 *
 * The windowed search that rides on `CONSOLE_HISTORY` only looks as far back as "Load older" can
 * reach; this one walks the whole `logs` directory, newest file first, and answers a page at a
 * time. [cursor] is the one the previous page ended on, opaque to Pano and the panel alike, or null
 * to start at the newest file; [limit] caps the matches and [budgetMs] the time one page may take.
 *
 * The answer comes back as the usual `FILE_RESULT`, paired by `eventId`. A node too old to know
 * the message never answers, and the timeout is what tells the panel to fall back to the windowed
 * search.
 */
class ConsoleSearchMessage(
    val serverUuid: String,
    val query: String,
    val cursor: String?,
    val limit: Int,
    val budgetMs: Int
) : NodeRequestMessage()
