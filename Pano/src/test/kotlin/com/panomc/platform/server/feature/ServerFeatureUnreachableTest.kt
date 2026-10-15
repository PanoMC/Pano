package com.panomc.platform.server.feature

import com.panomc.platform.ApiLevel
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.node.NodeKind
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The "an old jar cannot reach this Pano" case, read from the stored protocol whether connected or not. */
class ServerFeatureUnreachableTest {
    private fun server(protocol: Int, granted: Boolean = true) = Server(
        id = 4, name = "Survival", motd = "", host = "h", port = 25565, playerCount = 0, maxPlayerCount = 0,
        type = ServerType.PAPER, version = "1.21", favicon = "", permissionGranted = granted, status = ServerStatus.OFFLINE,
        startTime = 0, aesKey = "k", protocolVersion = protocol, pluginVersion = "alpha.65"
    )

    private fun node(protocol: Int, kind: NodeKind = NodeKind.REMOTE, agent: Boolean = false, approved: Boolean = true) = Node(
        id = 9, uuid = "u", name = "n", kind = kind, approved = approved, protocolVersion = protocol, aesKey = "k", agent = agent
    )

    @Test
    fun `a plugin below the minimum protocol cannot reach this Pano`() {
        assertTrue(ServerFeatureResolver.unreachablePlugin(server(ApiLevel.MIN_MC_PROTOCOL - 1)))
    }

    @Test
    fun `a plugin at the minimum is reachable even when older than the current protocol`() {
        assertFalse(ServerFeatureResolver.unreachablePlugin(server(ApiLevel.MIN_MC_PROTOCOL)))
    }

    private fun managed(pluginVersion: String?, protocol: Int = 1, state: ServerProcessState? = ServerProcessState.RUNNING, startedAt: Long? = 0L) =
        server(protocol).copy(
            kind = ServerKind.MANAGED, nodeId = 3, pluginVersion = pluginVersion, processState = state, processStartedAt = startedAt
        )

    @Test
    fun `a managed server whose plugin never announced itself is not too old`() {
        assertFalse(ServerFeatureResolver.unreachablePlugin(managed(pluginVersion = null)))
        assertTrue(ServerFeatureResolver.unreachablePlugin(managed(pluginVersion = "alpha.10")))
    }

    @Test
    fun `a managed server is missing its plugin only after it ran for a while without one`() {
        val grace = ServerFeatureResolver.MISSING_PLUGIN_GRACE_MS

        assertTrue(ServerFeatureResolver.missingPlugin(managed(null), connected = false, now = grace))
        assertFalse(ServerFeatureResolver.missingPlugin(managed(null), connected = false, now = grace - 1))
        assertFalse(ServerFeatureResolver.missingPlugin(managed(null, startedAt = null), connected = false, now = grace))
        assertFalse(ServerFeatureResolver.missingPlugin(managed(null, state = ServerProcessState.INSTALLING), connected = false, now = grace))
        assertFalse(ServerFeatureResolver.missingPlugin(managed("alpha.65"), connected = false, now = grace))
        assertFalse(ServerFeatureResolver.missingPlugin(managed(null), connected = true, now = grace))
        assertFalse(ServerFeatureResolver.missingPlugin(server(1).copy(pluginVersion = null), connected = false, now = grace))
    }

    @Test
    fun `a server nobody accepted is not flagged`() {
        assertFalse(ServerFeatureResolver.unreachablePlugin(server(ApiLevel.MIN_MC_PROTOCOL - 1, granted = false)))
    }

    @Test
    fun `a node below the minimum is flagged with its jar path`() {
        val info = ServerFeatureResolver.unreachableNodeInfo(node(ApiLevel.MIN_NODE_PROTOCOL - 1), true)!!

        assertEquals(9L, info.getLong("nodeId"))
        assertFalse(info.getBoolean("agent"))
        assertEquals("/api/v1/node/pano-node.jar", info.getString("downloadPath"))
    }

    @Test
    fun `an agent below the minimum points to the agent jar`() {
        val info = ServerFeatureResolver.unreachableNodeInfo(node(ApiLevel.MIN_NODE_PROTOCOL - 1, agent = true), true)!!

        assertTrue(info.getBoolean("agent"))
        assertEquals("/api/v1/node/pano-agent.jar", info.getString("downloadPath"))
    }

    @Test
    fun `a current node is not flagged`() {
        assertNull(ServerFeatureResolver.unreachableNodeInfo(node(ApiLevel.MIN_NODE_PROTOCOL), true))
    }

    @Test
    fun `the local node Pano manages itself is not flagged but a pinned one is`() {
        val local = node(ApiLevel.MIN_NODE_PROTOCOL - 1, kind = NodeKind.LOCAL)

        assertNull(ServerFeatureResolver.unreachableNodeInfo(local, true))
        assertTrue(ServerFeatureResolver.unreachableNode(local, false))
    }

    @Test
    fun `an unapproved node is not flagged`() {
        assertNull(ServerFeatureResolver.unreachableNodeInfo(node(1, approved = false), true))
    }
}
