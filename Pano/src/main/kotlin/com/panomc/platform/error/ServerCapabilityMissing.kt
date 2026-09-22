package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The linked server does not support the feature the panel asked for.
 *
 * Capabilities are announced by the plugin on connect, so this means the plugin is too old for the
 * feature, the platform cannot provide it (a proxy has no gamemode), or the admin turned it off in
 * the plugin config. Retrying will not help until the server announces the capability, which is
 * why this is a 400 and not a 503.
 */
class ServerCapabilityMissing(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
