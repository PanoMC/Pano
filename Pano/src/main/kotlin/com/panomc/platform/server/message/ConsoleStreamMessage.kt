package com.panomc.platform.server.message

import com.panomc.platform.server.PlatformMessage

/**
 * Turns the plugin's console stream on or off.
 *
 * Sent as `CONSOLE_STREAM`. The plugin starts disabled after every connect, flushes its own local
 * buffer when it is switched on, and stops sending lines when it is switched off. Pano switches it
 * on for the first panel subscriber and off a grace period after the last one leaves, so an idle
 * install pays nothing for the feature.
 */
data class ConsoleStreamMessage(
    val enabled: Boolean
) : PlatformMessage
