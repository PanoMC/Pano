package com.panomc.platform.node

import java.util.UUID

/**
 * Base of everything a node sends to Pano.
 *
 * [eventId] is echoed back on the response so a node can pair a reply with its request, the same
 * way the Minecraft plugin protocol does. Fire-and-forget events simply leave it unset.
 */
abstract class NodeEventRequest {
    val eventId: UUID? = null
}
