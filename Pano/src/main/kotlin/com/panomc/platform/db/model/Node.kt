package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.node.NodeBootstrap
import com.panomc.platform.node.NodeKind
import com.panomc.platform.node.NodeRuntime
import com.panomc.platform.node.NodeStatus
import com.panomc.platform.node.dto.NodeResources
import io.vertx.core.json.JsonObject

/**
 * A host that can run managed servers, i.e. one `pano-node` daemon Pano has paired with.
 *
 * The row is written when the daemon pairs over `POST /api/node/connect` and then updated from its
 * hellos; it outlives every individual WebSocket connection, which is what lets the panel show a
 * node that is currently down together with the servers stranded on it.
 */
data class Node(
    val id: Long = -1,
    /** Stable id the node knows itself by; never reused, unlike the auto-increment [id]. */
    var uuid: String,
    var name: String,
    var kind: NodeKind,
    /** How this node was installed. Display only, see [NodeBootstrap]. */
    var bootstrap: NodeBootstrap = NodeBootstrap.MANUAL,
    var runtime: NodeRuntime = NodeRuntime.PROCESS,
    var status: NodeStatus = NodeStatus.OFFLINE,
    /**
     * Whether an admin let this node in. A node that paired with a bootstrap token Pano itself
     * issued is approved immediately; one that used the rotating pairing code waits, exactly like
     * a server connect request.
     */
    var approved: Boolean = false,
    var version: String? = null,
    var protocolVersion: Int = 1,
    var os: String? = null,
    var arch: String? = null,
    var hostname: String? = null,
    var remoteAddress: String? = null,
    var dataPath: String? = null,
    /** Shared secret of this node's socket. Never leaves the platform, see [toPublicJsonObject]. */
    val aesKey: String,
    var resources: NodeResources = NodeResources.EMPTY,
    val addedTime: Long = System.currentTimeMillis(),
    var acceptedTime: Long = 0,
    var lastSeen: Long = 0,
    /**
     * Whether this daemon is a Pano Agent: a node dedicated to one existing server, run as
     * `pano-agent.jar` from that server's folder and paired through an agent code. It is approved
     * on pairing (the code came from an admin), adopts that one server in place on its first
     * connect, and is never listed as a node -- the panel shows only its server, with the node's
     * powers behind it.
     * Deleting that server uninstalls the agent and drops this row.
     */
    var agent: Boolean = false
) : DBEntity() {
    /**
     * JSON view of this node that is safe to hand out over the panel API and the panel WebSocket.
     *
     * [aesKey] decrypts and forges everything on that node's socket — which can start processes on
     * someone's machine — so it is stripped here, the same way [Server.toPublicJsonObject] strips
     * the server key. Every panel response that returns a node goes through this.
     */
    fun toPublicJsonObject(): JsonObject = JsonObject.mapFrom(this).apply { remove("aesKey") }

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean = other is Node && other.id == this.id
}
