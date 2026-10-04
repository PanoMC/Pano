package com.panomc.platform.server.message

import com.panomc.platform.server.ServerRequestMessage

/**
 * Asks a connected plugin for one page of its server's console history (`CONSOLE_HISTORY`).
 *
 * A request rather than a push, because the panel is waiting on an HTTP response. It exists for
 * the history Pano cannot have: the plugin streams its console only while somebody is watching,
 * so a server that crashed overnight wrote the reason into its own log file and into nothing
 * else. [skip] is how many of the newest lines the panel already shows, which is what makes the
 * page after this one a different page.
 *
 * The answer comes back as `CONSOLE_HISTORY_RESULT` carrying the same `eventId`. A plugin too old
 * to know the message simply never answers, which the caller treats as no history rather than as
 * a failure.
 */
class ConsoleHistoryMessage(
    val limit: Int,
    val skip: Int = 0,
    /**
     * Find text from the panel's console, already normalised (§2.4.20). Null is the plain page;
     * anything else asks the plugin to search the window "Load older" could reach, with [skip] and
     * [limit] counting matches. A plugin too old to know the field sends the plain page instead,
     * which Pano filters again on arrival.
     */
    val query: String? = null
) : ServerRequestMessage()
