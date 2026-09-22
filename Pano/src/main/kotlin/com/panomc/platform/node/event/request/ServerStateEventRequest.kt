package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest

/** A managed server's process changed state. Sent on every transition, not on a timer. */
data class ServerStateEventRequest(
    val serverUuid: String? = null,
    val state: String? = null,
    val exitCode: Int? = null,
    val pid: Long? = null,
    val since: Long? = null,
    /**
     * When the process started, epoch ms: sent with RUNNING only, null otherwise and from an older
     * node — Pano then falls back to [since]. Where a managed server's uptime comes from.
     */
    val startedAt: Long? = null,
    /**
     * Why it crashed, as the node read it off the console: the first error line of the tail it
     * sent with this frame.
     *
     * On the state rather than left to Pano to find in its own buffer, because the console batch
     * and this frame are handled concurrently — a notification that went looking would sometimes
     * look a moment too early. Null from an older node, and then Pano falls back to the buffer.
     */
    val reason: String? = null,
    /**
     * Whether this process was inherited from a daemon that is gone (SM-51, §2.4.16).
     *
     * Null from a node too old to report it, which is read as false: a daemon that has never
     * heard of adoption has only ever run processes it started itself.
     */
    val adopted: Boolean? = null,
    /** Whether the node can still write a command to this server's stdin. */
    val stdinAvailable: Boolean? = null,
    /**
     * Machine-readable cause of a stop, next to the prose in [reason] (SM-63, §2.4.28). The one
     * the node sends today is `JAVA_MISSING`: a start that found no runtime and could not (or was
     * not allowed to) download one. Null from an older node and for an ordinary stop.
     */
    val reasonCode: String? = null,
    /** The Java major a `JAVA_MISSING` start needed, when the node knows it. */
    val javaMajor: Int? = null
) : NodeEventRequest()
