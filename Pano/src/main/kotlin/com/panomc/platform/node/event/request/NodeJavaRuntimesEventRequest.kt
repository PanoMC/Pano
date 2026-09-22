package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest
import com.panomc.platform.node.dto.JavaRuntimeData

/**
 * A node's Java runtimes after they changed (SM-63, §2.4.28): the same list as the hello's, sent
 * after every install, removal and boot-time cleanup. Nullable like everything a node sends.
 */
data class NodeJavaRuntimesEventRequest(
    val javaRuntimes: List<JavaRuntimeData?>? = null
) : NodeEventRequest()
