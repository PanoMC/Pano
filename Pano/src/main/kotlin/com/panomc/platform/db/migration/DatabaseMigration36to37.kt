package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration36to37 : DatabaseMigration(
    36,
    37,
    "Add the server_backup table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createServerBackupTable()
    )

    private fun createServerBackupTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}server_backup` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `uuid` varchar(36) NOT NULL,
              `serverId` bigint NOT NULL,
              `nodeId` bigint NOT NULL,
              `name` varchar(255) NOT NULL,
              `sizeBytes` bigint NOT NULL DEFAULT 0,
              `sha256` varchar(64) DEFAULT NULL,
              `status` varchar(16) NOT NULL DEFAULT 'CREATING',
              `createdBy` bigint NOT NULL,
              `createdAt` bigint NOT NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `idx_server_backup_uuid` (`uuid`),
              KEY `idx_server_backup_server` (`serverId`),
              KEY `idx_server_backup_node` (`nodeId`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Backups of managed servers.';
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }
}
