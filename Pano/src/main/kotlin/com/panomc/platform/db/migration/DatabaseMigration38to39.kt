package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration38to39 : DatabaseMigration(
    38,
    39,
    "Add the server_alert table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createServerAlertTable()
    )

    private fun createServerAlertTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}server_alert` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `kind` varchar(32) NOT NULL,
              `serverId` bigint DEFAULT NULL,
              `nodeId` bigint DEFAULT NULL,
              `message` text NOT NULL,
              `createdAt` bigint NOT NULL,
              `resolvedAt` bigint DEFAULT NULL,
              PRIMARY KEY (`id`),
              KEY `idx_server_alert_created` (`createdAt`),
              KEY `idx_server_alert_server` (`serverId`),
              KEY `idx_server_alert_node` (`nodeId`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Things that went wrong on a server or node.';
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }
}
