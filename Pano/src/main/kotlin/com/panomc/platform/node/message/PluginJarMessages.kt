package com.panomc.platform.node.message

import com.panomc.platform.node.NodeRequestMessage

/**
 * The node's answer to "what plugins does this server have" when nothing inside it can be asked
 * (§2.4.17 B).
 *
 * Both are requests: the panel is waiting on an HTTP response, so the answer has to come back on
 * the socket rather than arrive later as an event. The message names are derived from the class
 * names (`PluginScanMessage` -> `PLUGIN_SCAN`), so they are the wire contract and the daemon moves
 * with them.
 */

/**
 * Asks a node to read every jar in a server's `plugins` (or `mods`) directory.
 *
 * Answers `{ ok, plugins: [{ file, name, version, main?, api?, description?, authors?, enabled,
 * kind }] }`, read out of each jar's own descriptor. It is the weaker of the two plugin lists on
 * purpose — jars on disk are what the server *will* load, not what it *has* loaded — so Pano
 * prefers the plugin's list wherever there is one and says in the panel where this one came from.
 */
class PluginScanMessage(
    val serverUuid: String
) : NodeRequestMessage()

/**
 * Asks a node to switch one jar off or on by renaming it `x.jar` <-> `x.jar.disabled`.
 *
 * Answers `{ ok, file, enabled, restartRequired }`, and `restartRequired` is always true: renaming
 * a file cannot unload a plugin the JVM has already loaded. That is exactly why the plugin's own
 * Bukkit toggle outranks this one when there is a plugin to ask.
 *
 * [file] names one entry of the plugins directory and is never a path.
 */
class PluginToggleMessage(
    val serverUuid: String,
    val file: String,
    val enabled: Boolean
) : NodeRequestMessage()
