package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Puts the Pano plugin into a server that already exists (`INSTALL_PANO_PLUGIN`).
 *
 * A fresh install carries its plugin inside [InstallServerMessage], because Pano chose the
 * software and therefore knew which build and which config path to send. An import cannot: the
 * software is only known once the node has inspected what it unpacked, so linking is a second
 * step Pano takes after `IMPORT_RESULT` — the same jar, the same credentials, a message later.
 */
data class InstallPanoPluginMessage(
    val serverUuid: String,
    val taskId: String,
    val spec: ManagedPluginSpec,
    /**
     * Whether the node starts the server once this install has ended, done or failed.
     *
     * Set only for the install that follows an import, which takes that import's start over
     * (see [com.panomc.platform.node.ImportStartHandoff]): started from the import's DONE
     * instead, the server booted while this jar was still downloading and came up without it.
     * Null everywhere else, which the node reads as "install, and nothing more".
     */
    val startAfter: Boolean? = null
) : NodeMessage
