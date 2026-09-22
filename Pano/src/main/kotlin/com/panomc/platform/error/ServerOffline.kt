package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The action needs a live plugin connection and the server is not connected right now.
 *
 * Unlike [ServerCapabilityMissing] this is transient: the same request will work once the server
 * comes back, so the panel disables the control instead of hiding it.
 */
class ServerOffline(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(409, statusMessage, extras)
