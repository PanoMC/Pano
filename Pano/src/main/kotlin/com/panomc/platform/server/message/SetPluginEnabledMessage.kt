package com.panomc.platform.server.message

import com.panomc.platform.server.PlatformMessage

/**
 * Asks the plugin to enable or disable another plugin on the same server
 * (`SET_PLUGIN_ENABLED`).
 *
 * Bukkit only: it is the one platform whose plugin manager can start and stop a plugin at runtime.
 * Pano rejects the request for any other server type before it ever gets here, and the plugin
 * ignores the message on platforms that cannot honour it.
 */
data class SetPluginEnabledMessage(
    val name: String,
    val enabled: Boolean
) : PlatformMessage
