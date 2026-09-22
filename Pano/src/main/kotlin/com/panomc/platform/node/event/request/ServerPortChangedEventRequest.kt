package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest

/**
 * `SERVER_PORT_CHANGED`: the node could not give a server the port Pano asked for.
 *
 * Pano reserves the number on the row before the install goes out, which stops two of its own
 * servers colliding but says nothing about the host — a remote node very often has 25565 taken by
 * something Pano has never heard of. The node probes before it writes `server.properties` and
 * reports what it did instead, because a port only the node knows is a row pointing players
 * somewhere nothing is listening.
 */
data class ServerPortChangedEventRequest(
    val serverUuid: String? = null,
    /** What Pano sent; kept for the log and the console line, never written to the row. */
    val requestedPort: Int? = null,
    val port: Int? = null,
    /** Why the requested port could not be used, in the node's own words. */
    val reason: String? = null
) : NodeEventRequest()
