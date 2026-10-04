package com.panomc.platform.server.message

import com.panomc.platform.server.PlatformMessage

/**
 * Asks the plugin to run [command] on the server's console sender.
 *
 * Sent as `EXECUTE_COMMAND`. The command travels as data in this payload and is never interpolated
 * into anything else on the Pano side. [issuedBy] is the panel username, which the plugin echoes
 * into the console so every viewer sees who ran what, and [requestId] correlates the request with
 * the log lines it produces.
 */
data class ExecuteCommandMessage(
    val command: String,
    val requestId: String,
    val issuedBy: String
) : PlatformMessage
