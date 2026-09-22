package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration41to42 : DatabaseMigration(
    41,
    42,
    "Add the server_plugin_install table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createServerPluginInstallTable()
    )

    // The unique key is the whole point of the table: a plugin directory is addressed by filename
    // and nothing else, so two rows for one jar would be two answers to "where did this come from".
    private fun createServerPluginInstallTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}server_plugin_install` (
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
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }
}
