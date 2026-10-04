package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Tells a node that it is being deleted and should remove itself from its host (`NODE_UNINSTALL`,
 * SM-64, §2.4.29 B).
 *
 * Reported back as a `NODE_UNINSTALL` task under [taskId]; the DONE frame carries `removedBytes`
 * and `manualSteps`, and the node closes its socket and exits with 78 right after it. Only ever
 * sent to a node that announced protocol 4 or newer — an older one would ignore it without a word.
 */
data class NodeUninstallMessage(
    val taskId: String
) : NodeMessage
