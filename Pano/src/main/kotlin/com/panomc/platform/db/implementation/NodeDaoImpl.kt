package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.NodeDao
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeRuntime
import com.panomc.platform.node.NodeStatus
import com.panomc.platform.node.dto.NodeResources
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class NodeDaoImpl : NodeDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `uuid` varchar(36) NOT NULL,
                              `name` varchar(255) NOT NULL,
                              `kind` varchar(16) NOT NULL,
                              `bootstrap` varchar(16) NOT NULL DEFAULT 'MANUAL',
                              `runtime` varchar(16) NOT NULL DEFAULT 'PROCESS',
                              `status` varchar(16) NOT NULL DEFAULT 'OFFLINE',
                              `approved` TINYINT(1) NOT NULL DEFAULT 0,
                              `version` varchar(64) DEFAULT NULL,
                              `protocolVersion` int NOT NULL DEFAULT 1,
                              `os` varchar(64) DEFAULT NULL,
                              `arch` varchar(32) DEFAULT NULL,
                              `hostname` varchar(255) DEFAULT NULL,
                              `remoteAddress` varchar(255) DEFAULT NULL,
                              `dataPath` varchar(512) DEFAULT NULL,
                              `aesKey` text NOT NULL,
                              `resources` text DEFAULT NULL,
                              `addedTime` bigint NOT NULL,
                              `acceptedTime` bigint NOT NULL DEFAULT 0,
                              `lastSeen` bigint NOT NULL DEFAULT 0,
                              `agent` TINYINT(1) NOT NULL DEFAULT 0,
                              PRIMARY KEY (`id`),
                              UNIQUE KEY `idx_node_uuid` (`uuid`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Hosts running the Pano node daemon.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(node: Node, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` " +
                    "(`uuid`, `name`, `kind`, `bootstrap`, `runtime`, `status`, `approved`, `version`, " +
                    "`protocolVersion`, `os`, `arch`, `hostname`, `remoteAddress`, `dataPath`, `aesKey`, " +
                    "`resources`, `addedTime`, `acceptedTime`, `lastSeen`, `agent`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    node.uuid,
                    node.name,
                    node.kind.name,
                    node.bootstrap.name,
                    node.runtime.name,
                    node.status.name,
                    if (node.approved) 1 else 0,
                    node.version,
                    node.protocolVersion,
                    node.os,
                    node.arch,
                    node.hostname,
                    node.remoteAddress,
                    node.dataPath,
                    node.aesKey,
                    node.resources.encode(),
                    node.addedTime,
                    node.acceptedTime,
                    node.lastSeen,
                    if (node.agent) 1 else 0
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): Node? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun getByUuid(uuid: String, sqlClient: SqlClient): Node? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `uuid` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(uuid))
            .coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun getAll(sqlClient: SqlClient): List<Node> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` ORDER BY `id` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun existsById(id: Long, sqlClient: SqlClient): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun count(sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countByStatus(status: NodeStatus, sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `status` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(status.name))
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun updateNameById(id: Long, name: String, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `name` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(name, id))
            .coAwait()
    }

    override suspend fun updateResourcesById(id: Long, resources: NodeResources, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `resources` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(resources.encode(), id))
            .coAwait()
    }

    override suspend fun updateApprovedById(id: Long, approved: Boolean, acceptedTime: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `approved` = ?, `acceptedTime` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(if (approved) 1 else 0, acceptedTime, id))
            .coAwait()
    }

    override suspend fun updateStatusById(id: Long, status: NodeStatus, lastSeen: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `status` = ?, `lastSeen` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(status.name, lastSeen, id))
            .coAwait()
    }

    override suspend fun updateLastSeenById(id: Long, lastSeen: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `lastSeen` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(lastSeen, id))
            .coAwait()
    }

    override suspend fun updateRemoteAddressById(id: Long, remoteAddress: String?, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `remoteAddress` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(remoteAddress, id))
            .coAwait()
    }

    override suspend fun updateHelloById(
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
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `version` = ?, `protocolVersion` = ?, " +
                "`os` = ?, `arch` = ?, `dataPath` = ?, `runtime` = ?, `resources` = ?, `lastSeen` = ? " +
                "WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    version,
                    protocolVersion,
                    os,
                    arch,
                    dataPath,
                    runtime.name,
                    resources.encode(),
                    lastSeen,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun updateAllForOffline(sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `status` = ? WHERE `status` <> ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(NodeStatus.OFFLINE.name, NodeStatus.OFFLINE.name))
            .coAwait()
    }
}
