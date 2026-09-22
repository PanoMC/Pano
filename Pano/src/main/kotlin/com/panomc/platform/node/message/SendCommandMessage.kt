package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Writes one line to a managed server's stdin (`SEND_COMMAND`).
 *
 * The command travels as data and is written to the process's own input stream; no shell is
 * involved anywhere on either side, which is what makes it safe to forward a string a person typed
 * without trying to sanitise it into something else.
 */
data class SendCommandMessage(
    val serverUuid: String,
    val command: String,
    val issuedBy: String
) : NodeMessage
