package com.panomc.platform.server

/**
 * A push Pano expects the plugin to answer.
 *
 * The plugin echoes [eventId] back inside its result event, which is how [ServerManager.request]
 * pairs the reply with the coroutine waiting for it — the same correlation the plugin already
 * uses in the other direction for every question it asks Pano. The id is assigned by
 * [ServerManager.request] and never by the caller, so two callers cannot pick the same one.
 */
abstract class ServerRequestMessage : PlatformMessage {
    var eventId: String? = null
}
