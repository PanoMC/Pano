package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Asks a node to delete a managed server's directory (`DELETE_SERVER`).
 *
 * Carries a task id because the deletion is reported back as a task: Pano keeps the server row
 * until the node says the files are gone, so a failed delete leaves a visible server rather than
 * an orphaned directory nobody can reach any more.
 */
data class DeleteServerMessage(
    val serverUuid: String,
    val taskId: String
) : NodeMessage
