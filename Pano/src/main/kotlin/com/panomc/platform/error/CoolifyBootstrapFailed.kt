package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * Coolify refused to deploy the node.
 *
 * The request Pano made was fine by Pano's rules and wrong by somebody else's, so the only useful
 * thing this can do is repeat what Coolify said: `extras` carries its `message` and the `taskId`
 * of the bootstrap task the panel is already following. A bare 400 was what this used to be, and
 * it left an operator staring at a failed deployment with no way to learn that a port field had
 * been rejected.
 */
class CoolifyBootstrapFailed(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
