package com.panomc.platform.node.dto

/**
 * One entry of the `servers` list a node sends in its hello: what it believes it is running.
 *
 * Both fields are nullable because this is untrusted input from a peer that may be newer than
 * this Pano; an entry Pano cannot read is dropped rather than failing the hello.
 */
data class NodeServerStateData(
    val uuid: String? = null,
    val state: String? = null,
    /** Process id, for a server the node is actually running. */
    val pid: Long? = null,
    /** Epoch millis the process has been in this state since. */
    val since: Long? = null,
    /** When the running process started, epoch ms; null when none is running or from an older node. */
    val startedAt: Long? = null,
    /**
     * Whether the node inherited this process from a daemon that is gone (SM-51, §2.4.16).
     *
     * Null from a node too old to say, which is the same as false: a daemon without adoption
     * never had a process it had not started itself.
     */
    val adopted: Boolean? = null,
    /** Whether a console command can still be written to it. Null from an older node means yes. */
    val stdinAvailable: Boolean? = null,
    /**
     * How the last run ended, for a server that is down: set when the node booked an exit that
     * happened while it was not running (SM-62). Null when it does not know.
     */
    val exitCode: Int? = null,
    /**
     * Whether the server was adopted where it already was (`IMPORT_SERVER` mode `IN_PLACE`) rather
     * than made under the node's data directory. Null from a node older than protocol 5, which
     * has no such servers.
     */
    val inPlace: Boolean? = null,
    /** The server directory's absolute path on the node's host. Null from a node older than 5. */
    val directory: String? = null
)
