package com.panomc.platform.node.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.event.request.NodeJavaRuntimesEventRequest
import com.panomc.platform.panel.PanelRealtimeHub

/**
 * A node's Java runtimes changed (`NODE_JAVA_RUNTIMES`, SM-63, §2.4.28).
 *
 * Only the runtime list moves: the rest of the resources snapshot is the hello's and stays. The
 * stored row is read fresh rather than taken from the connection's [Node], because the hello that
 * filled that instance may have been superseded by an earlier frame of this kind, and the row is
 * what the panel reads.
 *
 * A missing list is ignored rather than read as "no Java at all": that is what a malformed frame
 * looks like, and wiping a host's runtimes over one would make every Java select in the panel
 * empty until the next hello.
 */
@Event
class NodeJavaRuntimesEvent(
    private val databaseManager: DatabaseManager,
    private val panelRealtimeHub: PanelRealtimeHub
) : NodeEvent<NodeJavaRuntimesEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: NodeJavaRuntimesEventRequest, node: Node): NodeEventResponse? {
        val runtimes = request.javaRuntimes ?: return null

        val sqlClient = databaseManager.getSqlClient()

        val stored = databaseManager.nodeDao.getById(node.id, sqlClient) ?: return null

        val resources = stored.resources.withJavaRuntimes(runtimes)

        databaseManager.nodeDao.updateResourcesById(node.id, resources, sqlClient)

        node.resources = resources

        panelRealtimeHub.notifyNodeUpdated(node.id)

        return null
    }
}
