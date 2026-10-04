package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Tells a node to reinstall an existing server (`REINSTALL_SERVER`, SM-66).
 *
 * The same spec as [InstallServerMessage]; the name is what differs, and it is the whole point:
 * a node installs an `INSTALL_SERVER` straight into the server's directory, wiping it first, while
 * a `REINSTALL_SERVER` goes into a fresh directory and carries [InstallServerSpec.keep] over before
 * swapping it in. Every node since the first protocol understands this name.
 */
data class ReinstallServerMessage(
    val serverUuid: String,
    val taskId: String,
    val spec: InstallServerSpec
) : NodeMessage
