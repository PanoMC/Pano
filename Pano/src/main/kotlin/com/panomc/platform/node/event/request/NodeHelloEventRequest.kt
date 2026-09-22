package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest
import com.panomc.platform.node.dto.AgentLaunchData
import com.panomc.platform.node.dto.JavaRuntimeData
import com.panomc.platform.node.dto.NodePortRangeData
import com.panomc.platform.node.dto.NodeServerStateData

/**
 * What a node tells Pano about itself and its servers right after connecting.
 *
 * Every field is nullable: this is the first message from a peer whose version Pano does not know
 * yet, so a missing or renamed field has to degrade into "not reported" rather than throw while
 * the payload is being decoded.
 */
data class NodeHelloEventRequest(
    val version: String? = null,
    /**
     * SHA-256 of the jar the daemon is running from.
     *
     * The only way to tell two `local-build` daemons apart, which is every development build on
     * both sides of this connection. Null from a node too old to send it, and then "is there an
     * update" simply has no answer rather than a wrong one.
     */
    val jarSha256: String? = null,
    val protocolVersion: Int? = null,
    val os: String? = null,
    val arch: String? = null,
    val cpuCores: Int? = null,
    val memTotal: Long? = null,
    val diskTotal: Long? = null,
    val dataPath: String? = null,
    val runtime: String? = null,
    val javaRuntimes: List<JavaRuntimeData>? = null,
    val servers: List<NodeServerStateData>? = null,
    /**
     * The host's IANA time zone id (`ZoneId.systemDefault().id`), which is every one of this node's
     * servers' zone until its plugin says otherwise (SM-60, §2.4.25). Null from an older node.
     */
    val timeZone: String? = null,
    /**
     * Whether the node downloads a missing Java on its own (`java-auto-download`, SM-63). Null
     * from a node too old to have the feature, and its presence alone is one of the signals that
     * the node understands the Java messages (see [com.panomc.platform.node.NodeJavaSupport]).
     */
    val javaAutoDownload: Boolean? = null,
    /**
     * The ports the node's servers may bind, `{start, end}`, both inclusive: new servers on this
     * node are allocated inside it (see [com.panomc.platform.node.ServerPortAllocator]). Null from
     * a node too old to say, which keeps the allocation walking up from 25565.
     */
    val portRange: NodePortRangeData? = null,
    /** Feature names the node announces, e.g. `java-downloads`. Null from an older node. */
    val capabilities: List<String?>? = null,
    /**
     * Whether the daemon runs as a Pano Agent (protocol 5). Informational: Pano trusts the `agent`
     * flag on the node row, which only the agent pairing code sets.
     */
    val agent: Boolean? = null,
    /** The absolute path of the one server folder a Pano Agent runs; null for an ordinary node. */
    val agentServer: String? = null,
    /**
     * How a Pano Agent's admin said the server runs (SM-76): its memory and Java arguments, from
     * the agent's first run. Sent only while the agent has no server yet; null from anything else.
     */
    val agentLaunch: AgentLaunchData? = null
) : NodeEventRequest()
