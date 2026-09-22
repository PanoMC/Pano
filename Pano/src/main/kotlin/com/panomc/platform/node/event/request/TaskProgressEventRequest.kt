package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest

/** Progress of one long-running node job. */
data class TaskProgressEventRequest(
    val taskId: String? = null,
    val serverUuid: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val percent: Int? = null,
    val message: String? = null,
    val error: String? = null,
    /**
     * Whether the Pano plugin made it into the server this install created.
     *
     * Only on a DONE install of a server that was supposed to get one, and null from every other
     * frame and from a node too old to send it. It exists because an install that works and leaves
     * the server unlinked is still a DONE: the difference used to be visible nowhere but the node's
     * own log file, which on a remote host is a machine nobody is looking at.
     */
    val pluginInstalled: Boolean? = null,
    /** Bytes a finished `NODE_UNINSTALL` deleted (SM-64). Null on every other frame. */
    val removedBytes: Long? = null,
    /**
     * What the operator still has to run on the host after a `NODE_UNINSTALL` (SM-64): service
     * removal the node was not allowed to do, the data directory holding the retired marker.
     */
    val manualSteps: List<String?>? = null
) : NodeEventRequest()
