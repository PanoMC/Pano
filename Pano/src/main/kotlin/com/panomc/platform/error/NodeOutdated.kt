package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The node is connected but its daemon is too old for what was asked -- today, adopting a server in
 * place (`IN_PLACE`, protocol 5). Carries `requiredProtocolVersion`; updating the node from the
 * nodes page (or letting the automatic node update do it) is the fix. Nothing was created.
 */
class NodeOutdated(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(409, statusMessage, extras)
