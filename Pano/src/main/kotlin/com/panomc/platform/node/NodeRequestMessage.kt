package com.panomc.platform.node

/**
 * A push Pano expects an answer to.
 *
 * The node echoes [eventId] back inside a `FILE_RESULT`, which is how [NodeManager.request] pairs
 * the reply with the coroutine waiting for it — the same correlation the Minecraft plugin protocol
 * already uses in the other direction. The id is assigned by [NodeManager.request] and never by
 * the caller, so two callers cannot pick the same one.
 */
abstract class NodeRequestMessage : NodeMessage {
    var eventId: String? = null
}
