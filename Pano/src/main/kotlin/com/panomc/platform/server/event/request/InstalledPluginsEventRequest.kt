package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest
import com.panomc.platform.server.dto.ServerPluginData

/**
 * The full list of plugins or mods installed on a connected server (`INSTALLED_PLUGINS`).
 *
 * Always the complete list, never a delta: the plugin sends it right after connecting and again
 * after every toggle, so Pano replaces what it holds rather than merging.
 */
data class InstalledPluginsEventRequest(
    val plugins: List<ServerPluginData>? = null
) : ServerEventRequest()
