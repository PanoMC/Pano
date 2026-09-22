package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The action needs the node daemon and it is not connected right now.
 *
 * Transient, like [ServerOffline]: the node may be restarting, updating or simply unreachable, and
 * the same request will work once it comes back, so the panel disables the control instead of
 * hiding it. A node that is paired but not yet approved never connects, so it reports this too.
 */
class NodeOffline(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(409, statusMessage, extras)
