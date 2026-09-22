package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeRuntime
import com.panomc.platform.node.NodeStatus
import com.panomc.platform.node.dto.NodeResources
import io.vertx.sqlclient.SqlClient

abstract class NodeDao : Dao<Node>(Node::class.java) {
    abstract suspend fun add(
        node: Node,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): Node?

    abstract suspend fun getByUuid(
        uuid: String,
        sqlClient: SqlClient
    ): Node?

    abstract suspend fun getAll(
        sqlClient: SqlClient
    ): List<Node>

    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun count(
        sqlClient: SqlClient
    ): Long

    /** How many nodes are in one status right now, for the telemetry usage counters. */
    abstract suspend fun countByStatus(
        status: NodeStatus,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateNameById(
        id: Long,
        name: String,
        sqlClient: SqlClient
    )

    abstract suspend fun updateApprovedById(
        id: Long,
        approved: Boolean,
        acceptedTime: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateStatusById(
        id: Long,
        status: NodeStatus,
        lastSeen: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateLastSeenById(
        id: Long,
        lastSeen: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateRemoteAddressById(
        id: Long,
        remoteAddress: String?,
        sqlClient: SqlClient
    )

    /** Writes back everything a `NODE_HELLO` carries about the host it runs on. */
    abstract suspend fun updateHelloById(
        id: Long,
        version: String?,
        protocolVersion: Int,
        os: String?,
        arch: String?,
        dataPath: String?,
        runtime: NodeRuntime,
        resources: NodeResources,
        lastSeen: Long,
        sqlClient: SqlClient
    )

    /**
     * Replaces the stored resources snapshot on its own, for frames that change one part of it —
     * a `NODE_JAVA_RUNTIMES` after a Java install or removal (SM-63).
     */
    abstract suspend fun updateResourcesById(
        id: Long,
        resources: NodeResources,
        sqlClient: SqlClient
    )

    /** Forces every node offline, called on boot before any of them can reconnect. */
    abstract suspend fun updateAllForOffline(
        sqlClient: SqlClient
    )
}
