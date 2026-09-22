package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerPluginInstallDao
import com.panomc.platform.db.model.ServerPluginInstall
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerPluginInstallDaoImpl : ServerPluginInstallDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `serverId` bigint NOT NULL,
                              `filename` varchar(255) NOT NULL,
                              `source` varchar(32) NOT NULL,
                              `projectId` varchar(128) NOT NULL,
                              `projectName` varchar(255) DEFAULT NULL,
                              `pageUrl` varchar(512) DEFAULT NULL,
                              `versionId` varchar(128) DEFAULT NULL,
                              `versionNumber` varchar(128) DEFAULT NULL,
                              `publishedAt` varchar(64) DEFAULT NULL,
                              `identified` TINYINT(1) NOT NULL DEFAULT 0,
                              `taskId` varchar(36) DEFAULT NULL,
                              `createdBy` bigint DEFAULT NULL,
                              `installedAt` bigint NOT NULL,
                              PRIMARY KEY (`id`),
                              UNIQUE KEY `idx_server_plugin_install_file` (`serverId`, `filename`),
                              KEY `idx_server_plugin_install_task` (`taskId`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Where a managed server''s plugin jars came from.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun upsert(serverPluginInstall: ServerPluginInstall, sqlClient: SqlClient) {
        // One statement rather than a read and a branch: two panels installing the same plugin at
        // the same moment would otherwise both see "not there" and both insert.
        val query = "INSERT INTO `${getTablePrefix() + tableName}` " +
                "(`serverId`, `filename`, `source`, `projectId`, `projectName`, `pageUrl`, `versionId`, " +
                "`versionNumber`, `publishedAt`, `identified`, `taskId`, `createdBy`, `installedAt`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `source` = VALUES(`source`), `projectId` = VALUES(`projectId`), " +
                "`projectName` = VALUES(`projectName`), `pageUrl` = VALUES(`pageUrl`), " +
                "`versionId` = VALUES(`versionId`), `versionNumber` = VALUES(`versionNumber`), " +
                "`publishedAt` = VALUES(`publishedAt`), `identified` = VALUES(`identified`), " +
                "`taskId` = VALUES(`taskId`), `installedAt` = VALUES(`installedAt`)"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    serverPluginInstall.serverId,
                    serverPluginInstall.filename,
                    serverPluginInstall.source,
                    serverPluginInstall.projectId,
                    serverPluginInstall.projectName,
                    serverPluginInstall.pageUrl,
                    serverPluginInstall.versionId,
                    serverPluginInstall.versionNumber,
                    serverPluginInstall.publishedAt,
                    serverPluginInstall.identified,
                    serverPluginInstall.taskId,
                    serverPluginInstall.createdBy,
                    serverPluginInstall.installedAt
                )
            )
            .coAwait()
    }

    override suspend fun getByServerId(serverId: Long, sqlClient: SqlClient): List<ServerPluginInstall> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `serverId` = ? ORDER BY `filename`"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(Tuple.of(serverId)).coAwait()

        return rows.toEntities()
    }

    override suspend fun getByServerIdAndFilename(
        serverId: Long,
        filename: String,
        sqlClient: SqlClient
    ): ServerPluginInstall? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `serverId` = ? AND `filename` = ?"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(Tuple.of(serverId, filename)).coAwait()

        return rows.toEntities().firstOrNull()
    }

    override suspend fun getServerIdsWithRows(sqlClient: SqlClient): List<Long> {
        val query = "SELECT DISTINCT `serverId` FROM `${getTablePrefix() + tableName}` ORDER BY `serverId`"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute().coAwait()

        return rows.map { it.getLong(0) }
    }

    override suspend fun deleteByServerIdAndFilename(serverId: Long, filename: String, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `serverId` = ? AND `filename` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(serverId, filename)).coAwait()
    }

    override suspend fun deleteByServerId(serverId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `serverId` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(serverId)).coAwait()
    }

    override suspend fun deleteByTaskId(taskId: String, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `taskId` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(taskId)).coAwait()
    }

    override suspend fun clearTaskId(taskId: String, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `taskId` = NULL WHERE `taskId` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(taskId)).coAwait()
    }
}
