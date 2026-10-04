package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Tells a node whether the Pano plugin inside one of its servers is connected (`SERVER_PLUGIN_STATE`).
 *
 * The node cannot work this out for itself: the plugin's socket terminates at Pano, not at the
 * daemon watching the process. It needs it for one decision — a server whose plugin is connected
 * already reports its roster from the inside, so pinging its port would be a second and strictly
 * worse answer to a question that is already answered (§2.4.17 B).
 *
 * Pushed on every plugin connect and disconnect of a managed server, and again for every server of
 * a node that has just reconnected, because a daemon that restarted knows nothing.
 */
data class ServerPluginStateMessage(
    val serverUuid: String,
    val connected: Boolean
) : NodeMessage
