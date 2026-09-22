package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The node could not remove itself from its host, so it was not deleted (SM-64, §2.4.29 B).
 *
 * Carries the node's reason in `nodeError` — its own message, or `TIMEOUT`, `NODE_DISCONNECTED`,
 * `NODE_TOO_OLD`. Not `error`: extras are flattened into the response, and `error` is this class's
 * own code. Also carries `manualSteps` and `serverCount`, like [NodeOffline] does, so the panel
 * can offer "Remove from Pano anyway" (`force`) with the commands the operator will then have to
 * run. A 409: the node and every server on it are exactly as they were, apart from whatever the
 * node managed to stop.
 */
class NodeUninstallFailed(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(409, statusMessage, extras)
