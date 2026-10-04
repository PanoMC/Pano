package com.panomc.platform.server.event.request

import com.panomc.platform.server.ServerEventRequest

/**
 * `PANO_PLUGIN_UPDATE_RESULT`: how a self-update the plugin was asked for ended.
 *
 * Sent once the new jar is staged (or once it is clear it will not be), which is the end of
 * everything that can happen before a restart. [stagedVersion] is the version now waiting to load;
 * [mode] says how it will load — `update-folder` when Bukkit's own `plugins/update/` mechanism
 * takes over at the next boot, `swap-on-shutdown` when the plugin replaces its jar as the server
 * stops. Everything is nullable, as on every frame a plugin sends: an older or newer plugin must
 * degrade, not fail to decode.
 */
data class PanoPluginUpdateResultEventRequest(
    val taskId: String? = null,
    val ok: Boolean? = null,
    val error: String? = null,
    val stagedVersion: String? = null,
    val mode: String? = null
) : ServerEventRequest()
