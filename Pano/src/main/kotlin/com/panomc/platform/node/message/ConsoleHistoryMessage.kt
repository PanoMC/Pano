package com.panomc.platform.node.message

import com.panomc.platform.node.NodeRequestMessage

/**
 * Asks a node for the console scrollback it is holding for one server (`CONSOLE_HISTORY`).
 *
 * A request, because the panel is waiting on an HTTP response. It exists for the case Pano cannot
 * cover on its own: the node streams a server's output only while somebody is watching it, so a
 * server that crashed overnight wrote its reason into the node's ring buffer and into nothing
 * else. Opening the console then used to show an empty screen — the lines that would explain the
 * crash were a socket away and nobody ever asked for them.
 *
 * [skip] is how many of the newest lines the panel already has, so that asking again returns the
 * page before this one instead of the same one: that is how a console scrolls back past whatever
 * either side happens to be holding in memory.
 *
 * The answer comes back as the usual `FILE_RESULT`, paired by `eventId` like every other node
 * request: one reply name for everything is the protocol's rule, not an oversight.
 */
class ConsoleHistoryMessage(
    val serverUuid: String,
    val limit: Int,
    val skip: Int = 0,
    /**
     * Find text from the panel's console, already normalised (§2.4.20). Null is the plain page;
     * anything else asks the node to search the window "Load older" could reach, with [skip] and
     * [limit] counting matches. A node too old to know the field sends the plain page instead,
     * which Pano filters again on arrival.
     */
    val query: String? = null
) : NodeRequestMessage()
