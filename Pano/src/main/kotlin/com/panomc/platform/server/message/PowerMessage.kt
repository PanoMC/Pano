package com.panomc.platform.server.message

import com.panomc.platform.server.PlatformMessage

/**
 * Asks the plugin to stop or restart its own server (`POWER`).
 *
 * The plugin performs it through the platform's own shutdown API on the main thread. RESTART needs
 * a wrapper that actually brings the server back (the Spigot restart script); where there is none
 * the plugin falls back to stopping and says so in the log, because Pano cannot start a linked
 * server itself.
 */
data class PowerMessage(
    val action: String,
    val requestId: String,
    val issuedBy: String
) : PlatformMessage
