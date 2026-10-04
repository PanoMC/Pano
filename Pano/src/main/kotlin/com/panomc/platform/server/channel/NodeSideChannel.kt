package com.panomc.platform.server.channel

import com.panomc.platform.error.NodeOffline
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeMessage
import com.panomc.platform.node.NodeRequestMessage
import com.panomc.platform.server.feature.ServerFeatureSource
import io.vertx.core.json.JsonObject

/** The original channel: the daemon that owns the process, addressed over the node socket. */
class NodeSideChannel(
    override val nodeId: Long,
    override val serverUuid: String,
    private val nodeManager: NodeManager
) : ServerSideChannel {
    override val source = ServerFeatureSource.NODE

    override suspend fun request(message: NodeRequestMessage, timeoutMs: Long): JsonObject =
        nodeManager.request(nodeId, message, timeoutMs)

    override fun send(message: NodeMessage) {
        if (!nodeManager.sendMessage(nodeId, message)) {
            throw NodeOffline()
        }
    }
}
