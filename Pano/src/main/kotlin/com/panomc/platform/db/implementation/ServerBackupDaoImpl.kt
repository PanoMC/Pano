package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerBackupDao
import com.panomc.platform.db.model.ServerBackup
import com.panomc.platform.server.backup.BackupMode
import com.panomc.platform.server.backup.BackupScope
import com.panomc.platform.server.backup.ServerBackupStatus
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerBackupDaoImpl : ServerBackupDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `uuid` varchar(36) NOT NULL,
                              `serverId` bigint NOT NULL,
                              `nodeId` bigint DEFAULT NULL,
                              `name` varchar(255) NOT NULL,
                              `sizeBytes` bigint NOT NULL DEFAULT 0,
                              `sha256` varchar(64) DEFAULT NULL,
                              `status` varchar(16) NOT NULL DEFAULT 'CREATING',
                              `createdBy` bigint NOT NULL,
                              `createdAt` bigint NOT NULL,
                              `mode` varchar(16) NOT NULL DEFAULT 'FULL',
                              `scope` varchar(16) NOT NULL DEFAULT 'ALL',
                              `pinned` tinyint(1) NOT NULL DEFAULT 0,
                              `fileCount` bigint DEFAULT NULL,
                              `storedBytes` bigint DEFAULT NULL,
                              `include` text DEFAULT NULL,
                              `exclude` text DEFAULT NULL,
                              PRIMARY KEY (`id`),
                              UNIQUE KEY `idx_server_backup_uuid` (`uuid`),
                              KEY `idx_server_backup_server` (`serverId`),
                              KEY `idx_server_backup_node` (`nodeId`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Backups of managed servers.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(serverBackup: ServerBackup, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` " +
                    "(`uuid`, `serverId`, `nodeId`, `name`, `sizeBytes`, `sha256`, `status`, `createdBy`, `createdAt`, " +
                    "`mode`, `scope`, `pinned`, `fileCount`, `storedBytes`, `include`, `exclude`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.wrap(
                    listOf(
                        serverBackup.uuid,
                        serverBackup.serverId,
                        serverBackup.nodeId,
                        serverBackup.name,
                        serverBackup.sizeBytes,
                        serverBackup.sha256,
                        serverBackup.status.name,
                        serverBackup.createdBy,
                        serverBackup.createdAt,
                        serverBackup.mode.name,
                        serverBackup.scope.name,
                        if (serverBackup.pinned) 1 else 0,
                        serverBackup.fileCount,
                        serverBackup.storedBytes,
                        JsonArray(serverBackup.include).encode(),
                        JsonArray(serverBackup.exclude).encode()
                    )
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): ServerBackup? {
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

    override suspend fun getByUuid(uuid: String, sqlClient: SqlClient): ServerBackup? {
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

    override suspend fun getAllByServerId(serverId: Long, sqlClient: SqlClient): List<ServerBackup> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `serverId` = ? ORDER BY `createdAt` DESC, `id` DESC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(serverId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun updateResultByUuid(
        uuid: String,
        sizeBytes: Long,
        sha256: String?,
        status: ServerBackupStatus,
        mode: BackupMode,
        scope: BackupScope,
        fileCount: Long?,
        storedBytes: Long?,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `sizeBytes` = ?, `sha256` = ?, `status` = ?, " +
                "`mode` = ?, `scope` = ?, `fileCount` = ?, `storedBytes` = ? WHERE `uuid` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.wrap(
                    listOf(sizeBytes, sha256, status.name, mode.name, scope.name, fileCount, storedBytes, uuid)
                )
            )
            .coAwait()
    }

    override suspend fun updatePinnedById(id: Long, pinned: Boolean, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `pinned` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(if (pinned) 1 else 0, id))
            .coAwait()
    }

    override suspend fun failCreatingByServerId(serverId: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `status` = ? " +
                "WHERE `serverId` = ? AND `status` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ServerBackupStatus.FAILED.name,
                    serverId,
                    ServerBackupStatus.CREATING.name
                )
            )
            .coAwait()
    }

    override suspend fun countCreatedSince(since: Long, sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `createdAt` >= ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(since))
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

    override suspend fun deleteByServerId(serverId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `serverId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(serverId))
            .coAwait()
    }

    override suspend fun deleteByNodeId(nodeId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `nodeId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(nodeId))
            .coAwait()
    }
}
